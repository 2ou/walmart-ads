package com.ai.walmartads.walmartlisting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.walmartads.workspace.LingXingMcpCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 领星 MCP 网关客户端：无状态 Streamable HTTP JSON-RPC。
 * 双层信封：HTTP body → result.content[0].text（JSON 字符串）→ data.data.{count,list}。
 *
 * <p>领星网关不会返回可复用的 {@code Mcp-Session-Id}。直接发送业务 {@code tools/call}，
 * 避免每次服务重启先执行 initialize/initialized，额外消耗认证侧请求额度。
 *
 * <p>在线商品同步走 {@link #fetchAllListings(String)}；广告三块（placement/keyword/campaign）走
 * {@link #fetchPlacement()} / {@link #fetchKeywords()} / {@link #fetchCampaigns()}。
 * 其余业务工具可走通用入口 {@link #callTool(String, Map)}，返回信封内层 {@code data.data}。
 */
public class LingXingClient {

    private static final Logger log = LoggerFactory.getLogger(LingXingClient.class);
    private static final String LISTING_TOOL_ID = "platform_listing_walmart_list";

    /**
     * 单次工具调用的最大尝试次数与退避间隔（毫秒）。
     *
     * <p>领星开放网关会把「限流 / 上游超时 / 网关抖动」表现为 HTTP 200 + 纯文本
     * （例如以 {@code call} 开头的提示），而不是 5xx。这类响应走到 Jackson 就是
     * {@code Unrecognized token 'call'}，且当天每小时都失败 —— 属于瞬态，值得重试。
     * 业务失败（{@code 领星 MCP 业务失败}）不重试，重试也不会变。
     */
    private static final int MAX_ATTEMPTS = 3;
    private static final long[] RETRY_BACKOFF_MS = {800L, 2000L};

    /** 默认限流冷却分钟数。必须严格小于调度周期（60 分钟），否则下一个整点永远落在冷却窗内。 */
    private static final long DEFAULT_COOLDOWN_MINUTES = 50L;
    /** 冷却上限；超过调度周期就会形成「永远自愈不了」的死循环，这里硬性夹住。 */
    private static final long MAX_COOLDOWN_MINUTES = 59L;

    /** 无数据库协调器时的兼容限速；官方 FAQ 明确 QPS=1，这里取 30 次/分钟留足余量。 */
    private static final long DEFAULT_CALLS_PER_MINUTE = 30L;
    private static final long MIN_CALLS_PER_MINUTE = 6L;
    private static final long MAX_CALLS_PER_MINUTE = 60L;

    /**
     * 无法从响应头读到 retry-after 时的兜底冷却。正常情况下以服务端 retry-after 为准。
     */
    private static final long RATE_LIMIT_COOLDOWN_MS = resolveCooldownMs();
    /**
     * 无数据库协调器时的进程内兜底冷却。作用域只保存不可逆指纹，不包含原始 MCP Key。
     */
    private static final Map<String, Long> RATE_LIMITED_UNTIL = new ConcurrentHashMap<>();

    /** 无数据库协调器时的进程内预约时槽；生产环境由数据库协调器执行跨业务、跨实例串行。 */
    private static final long CALL_INTERVAL_MS = resolveCallIntervalMs();
    private static final Object CALL_SLOT_LOCK = new Object();
    private static long nextCallSlotAt = 0L;
    private static final AtomicLong REQUEST_IDS = new AtomicLong(1L);
    /** 所有业务域共用连接池，避免按商品/订单/广告/评论反复创建认证连接。 */
    private static final HttpClient SHARED_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_2)
            .build();
    /** 异常信息里保留的原始响应片段长度，便于事后定位网关到底回了什么。 */
    private static final int PREVIEW_CHARS = 160;

    /** 沃尔玛广告报告工具强制必填 advertiserIds，且 SP 系各工具类型不一致：
     *  page_type 是 string 数组，keyword/campaign 是 integer 数组，传错会 422。 */
    private static final List<String> AD_CAMPAIGN_TYPES = List.of(
            "sponsoredProducts-auto", "sponsoredProducts-manual");

    /** 冷却/限流是「等一会儿再来」而不是业务失败，调度层据此区分 FAILED 与 DEFERRED。 */
    public static class RateLimitedException extends IllegalStateException {
        public RateLimitedException(String message) {
            super(message);
        }
    }

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final int pageSize;
    private final List<Long> advertiserIds;
    private final String rateLimitScope;
    /** 业务类目（PRODUCTS/SALES/ADS/REVIEWS…），用于把本地兜底冷却隔离到单类目。 */
    private final String category;
    private final LingXingMcpCoordinator coordinator;

    public LingXingClient(
            String baseUrl,
            String apiKey,
            int pageSize,
            int connectTimeoutSeconds,
            int readTimeoutSeconds,
            ObjectMapper objectMapper,
            List<Long> advertiserIds
    ) {
        this(baseUrl, apiKey, pageSize, connectTimeoutSeconds, readTimeoutSeconds,
                objectMapper, advertiserIds, null, null);
    }

    /**
     * @param category 业务类目（PRODUCTS/SALES/ADS/REVIEWS/WORKSPACE），用于隔离限流冷却；
     *                 传 null 表示不区分的通用通道。
     */
    public LingXingClient(
            String baseUrl,
            String apiKey,
            int pageSize,
            int connectTimeoutSeconds,
            int readTimeoutSeconds,
            ObjectMapper objectMapper,
            List<Long> advertiserIds,
            String category
    ) {
        this(baseUrl, apiKey, pageSize, connectTimeoutSeconds, readTimeoutSeconds,
                objectMapper, advertiserIds, category, null);
    }

    public LingXingClient(
            String baseUrl,
            String apiKey,
            int pageSize,
            int connectTimeoutSeconds,
            int readTimeoutSeconds,
            ObjectMapper objectMapper,
            List<Long> advertiserIds,
            String category,
            LingXingMcpCoordinator coordinator
    ) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.pageSize = pageSize;
        this.objectMapper = objectMapper;
        this.advertiserIds = advertiserIds == null ? List.of() : advertiserIds;
        this.coordinator = coordinator;
        this.category = category == null || category.isBlank()
                ? null : category.trim().toUpperCase(java.util.Locale.ROOT);
        String categorySuffix = this.category == null ? "" : "@" + this.category;
        this.rateLimitScope = coordinator == null
                ? "mcp-" + Integer.toUnsignedString((String.valueOf(baseUrl) + "|" + String.valueOf(apiKey)).hashCode(), 16) + categorySuffix
                : coordinator.fingerprint(baseUrl, apiKey) + categorySuffix;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(SHARED_HTTP_CLIENT);
        factory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("X-Mcp-Key", apiKey)
                .defaultHeader("Accept", "application/json, text/event-stream")
                .build();
    }

    public List<WalmartListingDto> fetchAllListings(String storeId) {
        if (storeId == null || storeId.isBlank()) {
            throw new IllegalArgumentException("Walmart 店铺 ID 不能为空");
        }
        List<WalmartListingDto> all = new ArrayList<>();
        Set<String> pageFingerprints = new HashSet<>();
        int offset = 0;
        Integer reportedTotal = null;
        while (true) {
            JsonNode data = callTool(LISTING_TOOL_ID, Map.of(
                    "length", pageSize,
                    "offset", offset,
                    "store_ids", List.of(storeId)
            ));
            JsonNode listNode = data.path("list");
            List<WalmartListingDto> page = objectMapper.convertValue(
                    listNode, new TypeReference<List<WalmartListingDto>>() {
                    });
            if (page == null || page.isEmpty()) {
                break;
            }
            reportedTotal = listingTotal(data, reportedTotal);
            String fingerprint = listingPageFingerprint(page);
            if (!pageFingerprints.add(fingerprint)) {
                throw new IllegalStateException("领星 Listing 分页重复，已拒绝用不完整数据覆盖库存");
            }
            for (WalmartListingDto row : page) {
                if (row.storeId == null || !storeId.equals(row.storeId)) {
                    throw new IllegalStateException("领星 Listing 返回了不属于当前店铺的数据");
                }
                all.add(row);
            }
            offset += page.size();
            // count 曾出现缺失或错误返回 200 的情况。以“末页不足一页”为主终止条件，
            // count/total 只用于日志，避免第一页被误判成完整快照。
            if (!hasFullListingPage(page.size(), pageSize)) {
                break;
            }
        }
        log.info("领星 MCP 沃尔玛在线商品拉取完成：reportedTotal={}, fetched={}", reportedTotal, all.size());
        return all;
    }

    static boolean hasFullListingPage(int returnedRows, int requestedPageSize) {
        return returnedRows >= requestedPageSize;
    }

    static Integer listingTotal(JsonNode data, Integer fallback) {
        for (String field : List.of("count", "total", "totalCount", "total_count")) {
            JsonNode value = data.path(field);
            if (value.isIntegralNumber() && value.asInt() >= 0) {
                return value.asInt();
            }
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    // Try the next known total field.
                }
            }
        }
        return fallback;
    }

    private static String listingPageFingerprint(List<WalmartListingDto> page) {
        WalmartListingDto first = page.getFirst();
        WalmartListingDto last = page.getLast();
        return page.size() + "|" + first.sku + "|" + first.itemId + "|" + last.sku + "|" + last.itemId;
    }

    /**
     * 广告 - 页面类型切片（Carousel/Search In-grid/Buybox/Homepage 等）。
     * 维度：每行一个 (campaign × pageType)；首行是 pageType 为空的合计行，需跳过。
     * advertiserIds 为 **string** 数组。
     */
    public List<Map<String, Object>> fetchPlacement(int windowDays, String endDate, String startDate) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("advertiserIds", advertiserIds.stream().map(String::valueOf).toList());
        base.put("campaignType", AD_CAMPAIGN_TYPES);
        base.put("startDate", startDate);
        base.put("endDate", endDate);
        base.put("day", 14);
        return fetchPagedList("platform_page_type_splist", base,
                row -> isBlank(row.get("pageType")));
    }

    /**
     * 广告 - 真实搜索词（keywordName 是真实词，且带 targetingType 真值）。
     * 首行是 keywordName 为空的合计行，需跳过。advertiserIds 为 **integer** 数组。
     */
    public List<Map<String, Object>> fetchKeywords(int windowDays, String endDate, String startDate) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("advertiserIds", advertiserIds);
        base.put("campaignType", AD_CAMPAIGN_TYPES);
        base.put("startDate", startDate);
        base.put("endDate", endDate);
        base.put("day", 14);
        base.put("paging", true);
        return fetchPagedList("get_walmart_sp_keyword_report", base,
                row -> isBlank(row.get("keywordName")));
    }

    /**
     * 广告 - campaign 元数据（campaignId/name/status/platform + 窗口指标）。
     * 首行是 campaignId 为空的合计行，需跳过。advertiserIds 为 **integer** 数组。
     */
    public List<Map<String, Object>> fetchCampaigns(int windowDays, String endDate, String startDate) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("advertiserIds", advertiserIds);
        base.put("campaignType", AD_CAMPAIGN_TYPES);
        base.put("startDate", startDate);
        base.put("endDate", endDate);
        base.put("day", 14);
        base.put("paging", true);
        return fetchPagedList("platform_platform_sp_list", base,
                row -> isBlank(row.get("campaignId")));
    }

    /**
     * 通用分页拉取：循环 pageNum 直到 list 为空或不足一页；跳过合计行（skip 谓词命中）。
     * 返回原始 Map 行列表，供 Service 层按字段名解析（领星返回字段类型不统一，不在此强转）。
     */
    private List<Map<String, Object>> fetchPagedList(
            String toolId, Map<String, Object> baseParams, Predicate<Map<String, Object>> skipRow) {
        List<Map<String, Object>> all = new ArrayList<>();
        int pageNum = 1;
        while (true) {
            Map<String, Object> params = new LinkedHashMap<>(baseParams);
            params.put("pageNum", pageNum);
            params.put("pageSize", pageSize);
            JsonNode data = callTool(toolId, params);
            JsonNode listNode = data.path("list");
            if (listNode == null || listNode.isMissingNode() || !listNode.isArray() || listNode.isEmpty()) {
                break;
            }
            List<Map<String, Object>> page = objectMapper.convertValue(
                    listNode, new TypeReference<List<Map<String, Object>>>() {
                    });
            if (page == null || page.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : page) {
                if (skipRow.test(row)) {
                    continue;
                }
                all.add(row);
            }
            if (page.size() < pageSize) {
                break;
            }
            pageNum++;
        }
        log.info("领星 MCP 广告拉取完成 toolId={}, rows={}", toolId, all.size());
        return all;
    }

    /**
     * 通用业务工具调用：{@code tools/call → action(toolId)}，返回信封内层 {@code data.data}。
     *
     * @param toolId 领星工具 ID，如 {@code platform_v2_page_list}
     * @param params 工具入参；空调用传 {@code Map.of()}
     * @return {@code data.data} 节点；调用失败抛 {@link IllegalStateException}
     */
    public JsonNode callTool(String toolId, Map<String, Object> params) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("toolId", toolId);
        arguments.put("params", params == null ? Map.of() : params);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", REQUEST_IDS.getAndIncrement());
        envelope.put("method", "tools/call");
        envelope.put("params", Map.of("name", "action", "arguments", arguments));
        String body = json(envelope);
        return callWithRetry(toolId, () -> {
            RawResponse raw = post(body, toolId);
            throwIfHttpFailure(raw, toolId);
            try {
                return extractData(raw.body(), toolId);
            } catch (IllegalStateException exception) {
                // Retry-After 只有和明确的限流响应同时出现时才代表冷却时间。
                // 成功响应即使带同名头也不能据此把整个 MCP 通道锁住。
                if (isRateLimited(exception)) {
                    applyRetryAfter(raw.retryAfter());
                }
                throw exception;
            }
        });
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("领星 MCP 请求体序列化失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * 把服务端 {@code Retry-After} 直接当作冷却截止时间。
     *
     * <p>实测领星被限流后会在响应头给出精确的解锁倒计时（本次约 4 小时 11 分，
     * 且倒计时递减、目标时刻固定），比我们自定的 50 分钟准得多。以前写死 50 分钟，
     * 每小时都撞在封禁窗里，等于白打。
     */
    private void applyRetryAfter(String retryAfter) {
        if (retryAfter == null || retryAfter.isBlank()) {
            return;
        }
        long seconds;
        try {
            seconds = Long.parseLong(retryAfter.trim());
        } catch (NumberFormatException ignored) {
            return;
        }
        if (seconds <= 0) {
            return;
        }
        if (coordinator != null) {
            // 服务端给出 Retry-After = 整个 key 被封，所有类目一起等；记账号级。
            java.time.LocalDateTime until = coordinator.recordRateLimit(
                    baseUrl, apiKey, null, seconds, "服务端 Retry-After=" + seconds + "s");
            log.warn("领星 MCP 返回 Retry-After={}s（约 {} 分钟），全账号通道 {} 冷却至 {}",
                    seconds, Math.max(1L, seconds / 60L), rateLimitScope, until);
            return;
        }
        long until = System.currentTimeMillis() + seconds * 1000L;
        Long previous = RATE_LIMITED_UNTIL.get(rateLimitScope);
        if (previous == null || until > previous) {
            RATE_LIMITED_UNTIL.put(rateLimitScope, until);
            log.warn("领星 MCP 返回 Retry-After={}s（约 {} 分钟），账号通道 {} 冷却至 {}",
                    seconds, Math.max(1L, seconds / 60L), rateLimitScope,
                    java.time.LocalTime.now().plusSeconds(seconds).withNano(0));
        }
    }

    /** 带退避重试的工具调用：直接调用业务工具；网关 search 不再作为同步前置条件。 */
    private <T> T callWithRetry(String toolId, Supplier<T> action) {
        ensureNotCoolingDown();
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                awaitPermit();
                return action.get();
            } catch (IllegalStateException exception) {
                last = exception;
                if (exception instanceof RateLimitedException) {
                    throw exception;
                }
                if (isRateLimited(exception)) {
                    if (coordinator != null) {
                        // 响应带 Retry-After 时，applyRetryAfter() 已经保存了服务端的精确时间；
                        // 不要再按本地兜底冷却重复记账，也不要覆盖原始 Retry-After 证据。
                        LingXingMcpCoordinator.RuntimeStatus runtime =
                                coordinator.status(baseUrl, apiKey, category);
                        java.time.LocalDateTime until = runtime.coolingDown()
                                ? runtime.limitedUntil()
                                : coordinator.recordRateLimit(baseUrl, apiKey, category, null,
                                        exception.getMessage());
                        log.warn("领星 MCP 命中限流，类目通道 {} 冷却至 {}：{}",
                                rateLimitScope, until, exception.getMessage());
                        throw new RateLimitedException(exception.getMessage());
                    }
                    // 有 Retry-After 时 applyRetryAfter 已经写好更长的冷却，这里不要覆盖短的。
                    long now = System.currentTimeMillis();
                    if (RATE_LIMITED_UNTIL.getOrDefault(rateLimitScope, 0L) <= now) {
                        RATE_LIMITED_UNTIL.put(rateLimitScope, now + RATE_LIMIT_COOLDOWN_MS);
                    }
                    long remainingMs = Math.max(0L,
                            RATE_LIMITED_UNTIL.getOrDefault(rateLimitScope, now) - now);
                    log.warn("领星 MCP 命中限流，账号通道 {} 冷却约 {} 分钟至 {}：{}",
                            rateLimitScope, (remainingMs + 59_999L) / 60_000L,
                            java.time.LocalTime.now().plusNanos(remainingMs * 1_000_000L).withNano(0),
                            exception.getMessage());
                    throw new RateLimitedException(exception.getMessage());
                }
                if (attempt >= MAX_ATTEMPTS || !isRetryable(exception)) {
                    log.error("领星 MCP 调用最终失败 toolId={}，第 {} 次尝试：{}", toolId, attempt, exception.getMessage());
                    throw exception;
                }
                long backoff = RETRY_BACKOFF_MS[Math.min(attempt - 1, RETRY_BACKOFF_MS.length - 1)];
                log.warn("领星 MCP 调用失败 toolId={}，第 {} 次，{}ms 后重试：{}",
                        toolId, attempt, backoff, exception.getMessage());
                sleep(backoff);
            }
        }
        throw last;
    }

    private void ensureNotCoolingDown() {
        if (coordinator != null) {
            LingXingMcpCoordinator.RuntimeStatus status = coordinator.status(baseUrl, apiKey, category);
            if (status.coolingDown()) {
                long minutes = Math.max(1L, java.time.Duration.between(
                        java.time.LocalDateTime.now(), status.limitedUntil()).plusSeconds(59).toMinutes());
                throw new RateLimitedException("领星 MCP 限流冷却中（" + rateLimitScope + "），约 "
                        + minutes + " 分钟后自动重试");
            }
            return;
        }
        long remaining = RATE_LIMITED_UNTIL.getOrDefault(rateLimitScope, 0L) - System.currentTimeMillis();
        if (remaining > 0) {
            long minutes = Math.max(1L, (remaining + 59_999L) / 60_000L);
            throw new RateLimitedException("领星 MCP 认证限流冷却中，约 " + minutes + " 分钟后自动重试");
        }
    }

    private void awaitPermit() {
        if (coordinator == null) {
            throttle();
            return;
        }
        try {
            coordinator.awaitPermit(baseUrl, apiKey, category);
        } catch (LingXingMcpCoordinator.CoolingDownException exception) {
            throw new RateLimitedException(exception.getMessage());
        }
    }

    /**
     * 解析冷却分钟数：系统属性 {@code lingxing.rateLimit.cooldownMinutes} 优先，
     * 其次环境变量 {@code LINGXING_RATE_LIMIT_COOLDOWN_MINUTES}，最后取默认 50 分钟。
     * 无论配置成多少，都会夹到 1~59 分钟，保证「下一个整点」一定能拿到一次干净的调度窗口。
     */
    private static long resolveCooldownMs() {
        String raw = System.getProperty("lingxing.rateLimit.cooldownMinutes");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("LINGXING_RATE_LIMIT_COOLDOWN_MINUTES");
        }
        long minutes = DEFAULT_COOLDOWN_MINUTES;
        if (raw != null && !raw.isBlank()) {
            try {
                long parsed = Long.parseLong(raw.trim());
                if (parsed >= 1 && parsed <= MAX_COOLDOWN_MINUTES) {
                    minutes = parsed;
                }
            } catch (NumberFormatException ignored) {
                // 配置不是合法整数时退回默认值，不因配置错误影响同步。
            }
        }
        return minutes * 60_000L;
    }

    /**
     * 解析每分鐘允许的调用次数：系统属性 {@code lingxing.rateLimit.callsPerMinute} 优先，
     * 其次环境变量 {@code LINGXING_RATE_LIMIT_CALLS_PER_MINUTE}，默认 30。
     * 无论配置多少都会夹到 6~60，确保满足官方 QPS=1。
     */
    private static long resolveCallIntervalMs() {
        String raw = System.getProperty("lingxing.rateLimit.callsPerMinute");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("LINGXING_RATE_LIMIT_CALLS_PER_MINUTE");
        }
        long perMinute = DEFAULT_CALLS_PER_MINUTE;
        if (raw != null && !raw.isBlank()) {
            try {
                long parsed = Long.parseLong(raw.trim());
                if (parsed >= MIN_CALLS_PER_MINUTE && parsed <= MAX_CALLS_PER_MINUTE) {
                    perMinute = parsed;
                }
            } catch (NumberFormatException ignored) {
                // 配置不是合法整数时退回默认值。
            }
        }
        return Math.max(1L, 60_000L / perMinute);
    }

    /**
     * 预约一个全局时槽再返回，保证任意 1 分钟内所有类目的调用总数不超过设定上限。
     * 不持锁 sleep：先占位、后等待，避免把并发线程串在锁上。
     */
    private static void throttle() {
        long slot;
        synchronized (CALL_SLOT_LOCK) {
            long now = System.currentTimeMillis();
            slot = Math.max(now, nextCallSlotAt);
            nextCallSlotAt = slot + CALL_INTERVAL_MS;
        }
        long wait = slot - System.currentTimeMillis();
        if (wait > 0) {
            sleep(wait);
        }
    }

    /** 业务失败（参数不合法 / 无权限 / 无数据）重试无意义，其余一律按瞬态处理。 */
    private static boolean isRetryable(IllegalStateException exception) {
        String message = exception.getMessage();
        return message == null || (!message.contains("业务失败")
                && !message.contains("status: 429")
                && !message.contains("请求过于频繁"));
    }

    /**
     * 该业务类目当前是否处于本机兜底冷却中。
     *
     * <p>注意：只有在没有 {@link LingXingMcpCoordinator}（无数据库运行环境）时才有效 ——
     * 有 coordinator 时冷却状态落在 {@code wm_ads_mcp_runtime}，请用
     * {@code coordinator.status(baseUrl, apiKey, category)} 查询。
     */
    public static boolean coolingDown(String category) {
        long now = System.currentTimeMillis();
        if (category == null || category.isBlank()) {
            return RATE_LIMITED_UNTIL.values().stream().anyMatch(until -> until > now);
        }
        String suffix = "@" + category.trim().toUpperCase(java.util.Locale.ROOT);
        return RATE_LIMITED_UNTIL.entrySet().stream()
                .anyMatch(entry -> entry.getKey().endsWith(suffix) && entry.getValue() > now);
    }

    /** 任意通道是否处于本机兜底冷却中（无 coordinator 时使用）。 */
    public static boolean anyCoolingDown() {
        long now = System.currentTimeMillis();
        return RATE_LIMITED_UNTIL.values().stream().anyMatch(until -> until > now);
    }

    private static boolean isRateLimited(IllegalStateException exception) {
        String message = exception.getMessage();
        return message != null && (message.contains("status: 429") || message.contains("请求过于频繁"));
    }

    private void throwIfExplicitRateLimit(RawResponse raw, String operation) {
        String body = raw.body() == null ? "" : raw.body();
        if (raw.statusCode() == 429 || body.contains("status: 429") || body.contains("请求过于频繁")) {
            IllegalStateException limited = new IllegalStateException("领星 MCP 网关调用失败 operation="
                    + operation + "，HTTP " + raw.statusCode() + "：" + preview(body));
            applyRetryAfter(raw.retryAfter());
            throw limited;
        }
    }

    private void throwIfHttpFailure(RawResponse raw, String operation) {
        throwIfExplicitRateLimit(raw, operation);
        if (raw.statusCode() >= 400) {
            throw new IllegalStateException("领星 MCP 网关调用失败 operation=" + operation
                    + "，HTTP " + raw.statusCode() + "：" + preview(raw.body()));
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("领星 MCP 重试被中断", interrupted);
        }
    }

    /**
     * 发一次 MCP 请求，同时把响应头 {@code Retry-After} 带回来。
     *
     * <p>用 {@code exchange} 而不是 {@code retrieve().body()}，因为后者拿不到响应头，
     * 而领星限流时正是靠这个头告诉我们准确的解锁时刻。
     */
    private RawResponse post(String body, String toolId) {
        try {
            return restClient.post()
                    .uri(baseUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.valueOf("text/event-stream"))
                    .body(body)
                    .exchange((httpRequest, response) -> {
                        String retryAfter = response.getHeaders().getFirst("Retry-After");
                        String text = new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8);
                        return new RawResponse(text, retryAfter, response.getStatusCode().value());
                    });
        } catch (Exception exception) {
            log.error("领星 MCP 调用失败 toolId={}: {}", toolId, exception.getMessage());
            throw new IllegalStateException("领星 MCP 调用失败：" + exception.getMessage(), exception);
        }
    }

    /** 原始响应：正文、限流倒计时与 HTTP 状态。 */
    private record RawResponse(String body, String retryAfter, int statusCode) {
    }

    /**
     * 拆双层信封并取 {@code data.data}。
     *
     * <p>领星把工具的业务结果再套一层：{@code data.data.{count,list}}（列表工具）或
     * {@code data.data.{total,list}}（keyword 工具）。调用方只关心最内层。
     */
    private JsonNode extractData(String response, String toolId) {
        if (response == null || response.isBlank()) {
            throw new IllegalStateException("领星 MCP 返回为空 toolId=" + toolId);
        }
        String json = response.trim();
        int firstBrace = json.indexOf('{');
        int lastBrace = json.lastIndexOf('}');
        if (firstBrace < 0 || lastBrace <= firstBrace) {
            // 网关把限流/上游错误回成纯文本（HTTP 仍为 200），这里必须先识别出来，
            // 否则 Jackson 只会抛 "Unrecognized token 'xxx'"，看不出网关到底说了什么。
            throw new IllegalStateException(
                    "领星 MCP 返回非 JSON toolId=" + toolId + "，原始响应前 " + PREVIEW_CHARS + " 字符："
                            + preview(json));
        }
        json = json.substring(firstBrace, lastBrace + 1);
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode content = root.path("result").path("content");
            if (content.isArray() && content.size() > 0) {
                String text = content.get(0).path("text").asText();
                if (text != null && !text.isBlank()) {
                    if (root.path("result").path("isError").asBoolean(false)
                            || text.stripLeading().startsWith("call failed")) {
                        throw new IllegalStateException("领星 MCP 网关调用失败 toolId=" + toolId + "：" + preview(text));
                    }
                    JsonNode envelope = objectMapper.readTree(text);
                    JsonNode data = envelope.path("data");
                    if (!data.path("success").asBoolean(true)) {
                        String message = data.path("msg").asText("未知错误");
                        throw new IllegalStateException(
                                "领星 MCP 业务失败 toolId=" + toolId + "：" + message);
                    }
                    return data.path("data");
                }
            }
            throw new IllegalStateException("领星 MCP 返回结构异常：缺少 result.content[0].text");
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("领星 MCP 返回解析失败：" + exception.getMessage(), exception);
        }
    }

    private static String preview(String text) {
        if (text == null) {
            return "<null>";
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= PREVIEW_CHARS ? flat : flat.substring(0, PREVIEW_CHARS) + "...";
    }

    private static boolean isBlank(Object value) {
        if (value == null) {
            return true;
        }
        if (value instanceof String s) {
            return s.isBlank();
        }
        return false;
    }
}
