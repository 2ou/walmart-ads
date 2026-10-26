package com.ai.walmartads.walmartlisting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * 领星 MCP 网关客户端：无状态 JSON-RPC，业务工具必须经 {@code tools/call → action(toolId)} 包一层。
 * 双层信封：HTTP body → result.content[0].text（JSON 字符串）→ data.data.{count,list}。
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
    /** 异常信息里保留的原始响应片段长度，便于事后定位网关到底回了什么。 */
    private static final int PREVIEW_CHARS = 160;

    /** 沃尔玛广告报告工具强制必填 advertiserIds，且 SP 系各工具类型不一致：
     *  page_type 是 string 数组，keyword/campaign 是 integer 数组，传错会 422。 */
    private static final List<String> AD_CAMPAIGN_TYPES = List.of(
            "sponsoredProducts-auto", "sponsoredProducts-manual");

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String baseUrl;
    private final int pageSize;
    private final List<Long> advertiserIds;
    private final Set<String> refreshedToolSchemas = ConcurrentHashMap.newKeySet();

    public LingXingClient(
            String baseUrl,
            String apiKey,
            int pageSize,
            int connectTimeoutSeconds,
            int readTimeoutSeconds,
            ObjectMapper objectMapper,
            List<Long> advertiserIds
    ) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.pageSize = pageSize;
        this.objectMapper = objectMapper;
        this.advertiserIds = advertiserIds == null ? List.of() : advertiserIds;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutSeconds * 1000);
        factory.setReadTimeout(readTimeoutSeconds * 1000);
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
        int offset = 0;
        int total = Integer.MAX_VALUE;
        while (offset < total) {
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
            total = data.path("count").asInt();
            for (WalmartListingDto row : page) {
                if (row.storeId == null || !storeId.equals(row.storeId)) {
                    throw new IllegalStateException("领星 Listing 返回了不属于当前店铺的数据");
                }
                all.add(row);
            }
            offset += page.size();
            if (all.size() >= total) {
                break;
            }
        }
        log.info("领星 MCP 沃尔玛在线商品拉取完成：total={}, fetched={}", total, all.size());
        return all;
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
        envelope.put("id", 1);
        envelope.put("method", "tools/call");
        envelope.put("params", Map.of("name", "action", "arguments", arguments));
        String body;
        try {
            body = objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("领星 MCP 请求体序列化失败：" + exception.getMessage(), exception);
        }
        return callWithRetry(toolId, () -> extractData(post(body, toolId), toolId));
    }

    /**
     * 带退避重试的工具调用：瞬态故障（非 JSON 响应 / 网络异常 / 5xx）重试，业务失败直接抛。
     *
     * <p>重试前清掉 Schema 缓存，让 {@link #refreshToolSchema(String)} 重新确认网关可用。
     */
    private JsonNode callWithRetry(String toolId, Supplier<JsonNode> action) {
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                refreshToolSchema(toolId);
                return action.get();
            } catch (IllegalStateException exception) {
                last = exception;
                if (attempt >= MAX_ATTEMPTS || !isRetryable(exception)) {
                    log.error("领星 MCP 调用最终失败 toolId={}，第 {} 次尝试：{}", toolId, attempt, exception.getMessage());
                    throw exception;
                }
                refreshedToolSchemas.remove(toolId);
                long backoff = RETRY_BACKOFF_MS[Math.min(attempt - 1, RETRY_BACKOFF_MS.length - 1)];
                log.warn("领星 MCP 调用失败 toolId={}，第 {} 次，{}ms 后重试：{}",
                        toolId, attempt, backoff, exception.getMessage());
                sleep(backoff);
            }
        }
        throw last;
    }

    /** 业务失败（参数不合法 / 无权限 / 无数据）重试无意义，其余一律按瞬态处理。 */
    private static boolean isRetryable(IllegalStateException exception) {
        String message = exception.getMessage();
        return message == null || !message.contains("业务失败");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("领星 MCP 重试被中断", interrupted);
        }
    }

    private void refreshToolSchema(String toolId) {
        if (!refreshedToolSchemas.add(toolId)) {
            return;
        }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "tools/call");
        envelope.put("params", Map.of(
                "name", "search",
                "arguments", Map.of("toolId", toolId)
        ));
        try {
            String response = post(objectMapper.writeValueAsString(envelope), "search:" + toolId);
            JsonNode root = objectMapper.readTree(response);
            JsonNode content = root.path("result").path("content");
            String text = content.isArray() && !content.isEmpty()
                    ? content.get(0).path("text").asText()
                    : null;
            JsonNode result = text == null || text.isBlank() ? null : objectMapper.readTree(text);
            if (result == null || !result.path("success").asBoolean(false)) {
                throw new IllegalStateException("领星 MCP 工具 Schema 刷新失败 toolId=" + toolId);
            }
        } catch (Exception exception) {
            refreshedToolSchemas.remove(toolId);
            if (exception instanceof IllegalStateException stateException) {
                throw stateException;
            }
            throw new IllegalStateException(
                    "领星 MCP 工具 Schema 刷新失败 toolId=" + toolId + "：" + exception.getMessage(), exception);
        }
    }

    private String post(String body, String toolId) {
        try {
            return restClient.post()
                    .uri(baseUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON, MediaType.valueOf("text/event-stream"))
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception exception) {
            log.error("领星 MCP 调用失败 toolId={}: {}", toolId, exception.getMessage());
            throw new IllegalStateException("领星 MCP 调用失败：" + exception.getMessage(), exception);
        }
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
