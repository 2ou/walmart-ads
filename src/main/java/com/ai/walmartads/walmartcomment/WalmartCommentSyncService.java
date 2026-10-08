package com.ai.walmartads.walmartcomment;

import com.ai.walmartads.config.LingXingProperties;
import com.ai.walmartads.importing.repository.SkuMappingRepository;
import com.ai.walmartads.walmartcomment.domain.WalmartComment;
import com.ai.walmartads.walmartcomment.domain.WalmartCommentSyncLog;
import com.ai.walmartads.walmartcomment.repository.WalmartCommentRepository;
import com.ai.walmartads.walmartcomment.repository.WalmartCommentSyncLogRepository;
import com.ai.walmartads.walmartlisting.LingXingClient;
import com.ai.walmartads.workspace.WorkspaceLingXingConfigService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 领星 MCP Walmart 店铺评价同步。
 *
 * <h2>数据源与写入语义</h2>
 * 工具 {@code platform_walmart_comment_list} 返回 {@code data.data.{total,pageCount,records[]}}。
 * 评价内容可被买家事后编辑，故按 {@code (platform, comment_id)} <b>upsert</b>，
 * 而不是订单 / 广告那种「按窗口整窗替换」—— 后者会把买家改过的评论刷回旧版本。
 *
 * <h2>归款</h2>
 * 领星的 {@code productMsku} / {@code tags} / {@code globalTagIds} 恒为空数组（实测 971 条 100% 空），
 * 故款号只能由 {@code platformProductNo}（Walmart Item ID）join {@code wm_ads_sku_mapping.item_id} 得到。
 * 批量取映射避免 N+1；未命中的历史下架款保留 {@code parentSku = null}，前端标「未归款」且不进款级统计。
 * 映射表只在商品重导时更新，故每次同步顺带 {@link #rescueUnmatched()} 回填一次历史未归款评论。
 *
 * <h2>窗口</h2>
 * 评价接口<b>无 31 天窗口上限</b>（与广告接口不同，实测 2459 天窗口正常返回），
 * 故 {@link #backfillAll()} 一次跑完全部历史（约 10 页）。此后走每日滚动窗口增量，
 * 窗口默认 7 天 —— 比订单的 3 天宽，覆盖评价的延迟发布与事后编辑。
 *
 * <h2>调度</h2>
 * 独立日级 cron（{@code walmart.ads.lingxing.comment-cron}，默认每日 08:30），
 * 不挂在小时级商品 / 订单 / 广告节奏上 —— 评价每日至多几十条变更，小时级只是无谓的 API 消耗。
 */
@Service
public class WalmartCommentSyncService {

    private static final Logger log = LoggerFactory.getLogger(WalmartCommentSyncService.class);

    private static final String SOURCE = "lingxing_mcp";
    private static final String TOOL_ID = "platform_walmart_comment_list";
    private static final String PLATFORM = "walmart";
    private static final int PAGE_SIZE = 200;
    /** IN 子句每批大小（MySQL 与 Hibernate 都友好）。 */
    private static final int IN_CHUNK = 200;
    /** 补窗下限：往前最多追一年，更深的历史由 backfillAll 负责。 */
    private static final int MAX_CATCHUP_DAYS = 365;

    private final LingXingProperties properties;
    private final WalmartCommentRepository commentRepository;
    private final WalmartCommentSyncLogRepository logRepository;
    private final SkuMappingRepository skuMappingRepository;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    @Autowired(required = false)
    private WorkspaceLingXingConfigService workspaceConfig;

    public WalmartCommentSyncService(
            LingXingProperties properties,
            WalmartCommentRepository commentRepository,
            WalmartCommentSyncLogRepository logRepository,
            SkuMappingRepository skuMappingRepository,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.commentRepository = commentRepository;
        this.logRepository = logRepository;
        this.skuMappingRepository = skuMappingRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.objectMapper = objectMapper;
    }

    /**
     * 店铺评价的独立日级调度。与小时级商品 / 订单 / 广告同步解耦：
     * 评价每日至多几十条变更，日级拉取已足够新鲜。
     */
    public void scheduledSync() {
        if (!currentConfig().configured()) {
            log.debug("领星 MCP 未配置（walmart.ads.lingxing.enabled=false 或缺少 api-key），跳过评价同步");
            return;
        }
        try {
            syncNow("CRON");
        } catch (Exception exception) {
            // 异常已写入 sync_log，这里只记录，避免定时任务抛错打断调度线程。
            log.error("定时同步 Walmart 评价失败：{}", exception.getMessage());
        }
    }

    /**
     * 按配置的滚动窗口同步（默认近 31 天）。
     *
     * <p>若上次成功窗口已被时间甩开（停机 / 连续失败导致缺口），窗口自动向前扩展到缺口起点 ——
     * 领星无第二来源可补。上限回溯 {@value #MAX_CATCHUP_DAYS} 天，更深的历史走 {@link #backfillAll()}。
     */
    public SyncSummary syncNow(String triggeredBy) {
        LocalDate end = LocalDate.now();
        int windowDays = properties.resolvedCommentWindowDays();
        LocalDate start = end.minusDays(windowDays - 1L);
        LocalDate lastSuccessEnd = logRepository.findLatestSuccessWindowEnd();
        if (lastSuccessEnd != null && lastSuccessEnd.isBefore(start)) {
            LocalDate catchUp = lastSuccessEnd.minusDays(1);
            LocalDate floor = end.minusDays(MAX_CATCHUP_DAYS);
            if (catchUp.isBefore(floor)) {
                catchUp = floor;
            }
            log.info("检测到评价数据缺口：上次成功窗口止于 {}，本次窗口自 {} 扩展至 {}（补窗）",
                    lastSuccessEnd, catchUp, end);
            start = catchUp;
        }
        return syncWindow(triggeredBy, start, end);
    }

    /** 首次回填：从配置起始日拉全部历史。评价接口无窗口上限，一次跑完。 */
    public SyncSummary backfillAll() {
        LocalDate start = properties.resolvedCommentBackfillStart();
        log.info("评价全量回填开始：{} ~ {}（评价接口无窗口上限，单次拉完）", start, LocalDate.now());
        return syncWindow("BACKFILL", start, LocalDate.now());
    }

    /**
     * 每周全历史对账。领星评价可能在发布日期数天后才进入接口，仅靠滚动窗口会永久漏数；
     * 全量接口当前仅数页，按 comment_id upsert 可安全、低成本地自动修复迟到评价。
     */
    public void scheduledReconcile() {
        if (!currentConfig().configured()) {
            return;
        }
        try {
            syncWindow("RECONCILE", properties.resolvedCommentBackfillStart(), LocalDate.now());
        } catch (Exception exception) {
            log.error("Walmart 评价全历史对账失败：{}", exception.getMessage());
        }
    }

    /**
     * 同步指定窗口。窗口无硬上限（评价接口允许跨年），但需 start ≤ end。
     */
    public SyncSummary syncWindow(String triggeredBy, LocalDate requestedStart, LocalDate requestedEnd) {
        WorkspaceLingXingConfigService.Config config = currentConfig();
        if (!config.configured()) {
            throw new IllegalStateException("领星 MCP 未配置，无法同步 Walmart 评价");
        }
        LingXingClient client = new LingXingClient(config.baseUrl(), config.apiKey(), PAGE_SIZE,
                10, 60, objectMapper, List.of());
        LocalDate end = requestedEnd == null ? LocalDate.now() : requestedEnd;
        LocalDate start = requestedStart == null
                ? end.minusDays(properties.resolvedCommentWindowDays() - 1L)
                : requestedStart;
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("窗口起始日不能晚于结束日：" + start + " ~ " + end);
        }

        WalmartCommentSyncLog record = new WalmartCommentSyncLog(
                LocalDateTime.now(), SOURCE, triggeredBy == null ? "MANUAL" : triggeredBy, start, end);
        logRepository.save(record);
        long begin = System.currentTimeMillis();
        try {
            List<Map<String, Object>> rows = fetch(client, config.storeId(), start, end);
            WriteResult written = persist(rows);
            int rescued = rescueUnmatched();
            long duration = System.currentTimeMillis() - begin;
            record.setStatus("SUCCESS");
            record.setFetchedCount(rows.size());
            record.setInsertedCount(written.insertedCount());
            record.setUpdatedCount(written.updatedCount());
            record.setMatchedSkuCount(written.matchedSkuCount());
            record.setUnmatchedCount(written.unmatchedCount());
            record.setAvgStar(written.avgStar());
            record.setFinishedAt(LocalDateTime.now());
            record.setDurationMs(duration);
            logRepository.save(record);
            log.info("Walmart 评价同步完成：窗口 {} ~ {}，拉取={}，新增={}，更新={}，归款={}，未归款={}，回填={}，均分={}，耗时={}ms",
                    start, end, rows.size(), written.insertedCount(), written.updatedCount(),
                    written.matchedSkuCount(), written.unmatchedCount(), rescued,
                    written.avgStar(), duration);
            return new SyncSummary(start, end, rows.size(), written.insertedCount(),
                    written.updatedCount(), written.matchedSkuCount(), written.unmatchedCount(),
                    rescued, written.avgStar(), "SUCCESS", null, duration, LocalDateTime.now());
        } catch (Exception exception) {
            long duration = System.currentTimeMillis() - begin;
            String message = rootMessage(exception);
            record.setStatus("FAILED");
            record.setErrorMessage(truncate(message));
            record.setFinishedAt(LocalDateTime.now());
            record.setDurationMs(duration);
            logRepository.save(record);
            log.error("Walmart 评价同步失败：{}", message);
            return new SyncSummary(start, end, 0, 0, 0, 0, 0, 0, BigDecimal.ZERO,
                    "FAILED", truncate(message), duration, LocalDateTime.now());
        }
    }

    // ---------------------------------------------------------------- 拉取

    /**
     * 分页拉取窗口内评价。领星返回 {@code records[]}（不是 {@code list[]}），
     * 且无合计行需跳过，故直接全量收集。
     */
    private List<Map<String, Object>> fetch(
            LingXingClient client, String storeId, LocalDate start, LocalDate end) {
        if (storeId == null || storeId.isBlank()) {
            throw new IllegalArgumentException("Walmart 店铺 ID 不能为空");
        }
        List<Map<String, Object>> all = new ArrayList<>();
        int pageNum = 1;
        int total = Integer.MAX_VALUE;
        int pageCount = 1;
        while (pageNum <= pageCount && all.size() < total) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("startDate", start.toString());
            params.put("endDate", end.toString());
            params.put("pageNum", pageNum);
            params.put("pageSize", PAGE_SIZE);
            params.put("storeIds", List.of(storeId));
            JsonNode data = client.callTool(TOOL_ID, params);
            total = data.path("total").asInt(total);
            pageCount = data.path("pageCount").asInt(pageNum);
            List<Map<String, Object>> page = objectMapper.convertValue(
                    data.path("records"), new TypeReference<List<Map<String, Object>>>() {
                    });
            if (page == null || page.isEmpty()) {
                break;
            }
            for (Map<String, Object> row : page) {
                if (!storeId.equals(text(row.get("storeId")))) {
                    throw new IllegalStateException("领星评价返回了不属于当前店铺的数据");
                }
                all.add(row);
            }
            pageNum++;
        }
        log.info("领星 Walmart 评价拉取完成：窗口 {} ~ {}，total={}，拉回={} 条", start, end, total, all.size());
        return all;
    }

    private WorkspaceLingXingConfigService.Config currentConfig() {
        if (workspaceConfig != null) return workspaceConfig.current();
        return new WorkspaceLingXingConfigService.Config(1L, properties.resolvedBaseUrl(), properties.apiKey(),
                properties.resolvedStoreId(), properties.resolvedPageSize(), properties.resolvedAdvertiserIds());
    }

    // ---------------------------------------------------------------- 落库

    /**
     * 按 {@code (platform, comment_id)} upsert。先批量取已有行避免逐条查库，
     * 再对新行 insert、旧行 update（内容可能已被买家编辑）。
     */
    // 以下三个成员为包级可见，仅用于单测直接驱动 upsert / 回填逻辑，
    // 绕开 {@link LingXingClient} 的 HTTP 与真实事务边界。

    WriteResult persist(List<Map<String, Object>> rows) {
        // 归款：一次批量取映射，避免 N+1。
        Set<String> itemIds = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String itemId = text(row.get("platformProductNo"));
            if (itemId != null) {
                itemIds.add(itemId);
            }
        }
        Map<String, String> styleByItem = resolveParentSkus(itemIds);

        return transactionTemplate.execute(status -> {
            Map<String, WalmartComment> existing = new HashMap<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Map<String, Object> row : rows) {
                String id = text(row.get("id"));
                if (id != null) {
                    seen.add(id);
                }
            }
            for (List<String> chunk : partition(new ArrayList<>(seen), IN_CHUNK)) {
                for (WalmartComment comment : commentRepository.findByPlatformAndCommentIdIn(PLATFORM, chunk)) {
                    existing.put(comment.getCommentId(), comment);
                }
            }

            int inserted = 0;
            int updated = 0;
            int matched = 0;
            int unmatched = 0;
            BigDecimal starSum = BigDecimal.ZERO;
            LocalDateTime now = LocalDateTime.now();
            for (Map<String, Object> row : rows) {
                String id = text(row.get("id"));
                if (id == null) {
                    continue;
                }
                String itemId = text(row.get("platformProductNo"));
                String parentSku = itemId == null ? null : styleByItem.get(itemId);
                WalmartComment entity = existing.get(id);
                if (entity == null) {
                    WalmartComment fresh = toEntity(row, parentSku, now, now);
                    if (fresh == null) {
                        continue;
                    }
                    entity = commentRepository.save(fresh);
                    inserted++;
                } else {
                    applyRow(entity, row, parentSku, now);
                    commentRepository.saveAndFlush(entity);
                    updated++;
                }
                if (entity.getParentSku() != null) {
                    matched++;
                } else {
                    unmatched++;
                }
                starSum = starSum.add(BigDecimal.valueOf(entity.getCommentStar()));
            }
            int total = inserted + updated;
            BigDecimal avg = total == 0
                    ? BigDecimal.ZERO
                    : starSum.divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            return new WriteResult(inserted, updated, matched, unmatched, avg);
        });
    }

    /**
     * 回填历史未归款评论的款号。
     *
     * <p>{@code wm_ads_sku_mapping} 只在商品重导时更新；早期评论的 Item ID 可能早已在映射表中，
     * 只是首次入库时映射表尚未包含。每次同步顺带回填一次，保证历史评论也能归到款。
     *
     * @return 本次补上的款号条数
     */
    int rescueUnmatched() {
        List<WalmartComment> unmatched = commentRepository.findByPlatformAndParentSkuIsNull(PLATFORM);
        if (unmatched.isEmpty()) {
            return 0;
        }
        Set<String> itemIds = unmatched.stream()
                .map(WalmartComment::getPlatformItemId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> styleByItem = resolveParentSkus(itemIds);
        if (styleByItem.isEmpty()) {
            return 0;
        }
        return transactionTemplate.execute(status -> {
            int fixed = 0;
            LocalDateTime now = LocalDateTime.now();
            for (WalmartComment comment : unmatched) {
                String parentSku = styleByItem.get(comment.getPlatformItemId());
                if (parentSku != null) {
                    comment.setParentSku(parentSku);
                    comment.setSyncedAt(now);
                    commentRepository.save(comment);
                    fixed++;
                }
            }
            if (fixed > 0) {
                log.info("回填 {} 条历史评价的款号（映射表已包含其 Item ID）", fixed);
            }
            return fixed;
        });
    }

    /** 批量把 Walmart Item ID 换成款号；未命中的 Item ID 不在返回 Map 中。 */
    private Map<String, String> resolveParentSkus(Set<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new HashMap<>();
        for (List<String> chunk : partition(new ArrayList<>(itemIds), IN_CHUNK)) {
            for (Object[] row : skuMappingRepository.findCurrentParentSkuByItemIds(chunk)) {
                if (row != null && row.length >= 2 && row[0] != null && row[1] != null) {
                    out.put(String.valueOf(row[0]), String.valueOf(row[1]));
                }
            }
        }
        return out;
    }

    private WalmartComment toEntity(Map<String, Object> row, String parentSku,
                                    LocalDateTime syncedAt, LocalDateTime createdAt) {
        LocalDate date = date(text(row.get("commentDate")));
        if (date == null) {
            // comment_date 为 NOT NULL，缺日期的行无法入库，跳过而非编造。
            log.warn("评价 {} 缺 commentDate，跳过入库", text(row.get("id")));
            return null;
        }
        return new WalmartComment(
                PLATFORM,
                text(row.get("id")),
                text(row.get("storeId")),
                text(row.get("storeName")),
                text(row.get("platformProductNo")),
                parentSku,
                date,
                intValue(row.get("commentStar")),
                text(row.get("commentTitle")),
                text(row.get("commentContent")),
                text(row.get("buyerName")),
                text(row.get("title")),
                text(row.get("image")),
                nullableInt(row.get("buyerFlag")),
                text(row.get("siteCode")),
                rawPayload(row),
                syncedAt,
                createdAt
        );
    }

    /** upsert 命中时刷新可变字段 —— 买家可事后编辑评论内容与标题。 */
    private void applyRow(WalmartComment entity, Map<String, Object> row, String parentSku, LocalDateTime now) {
        entity.setStoreName(text(row.get("storeName")));
        entity.setParentSku(parentSku);
        LocalDate date = date(text(row.get("commentDate")));
        if (date != null) {
            entity.setCommentDate(date);
        }
        int star = intValue(row.get("commentStar"));
        if (star > 0) {
            entity.setCommentStar(star);
        }
        entity.setCommentTitle(text(row.get("commentTitle")));
        entity.setCommentContent(text(row.get("commentContent")));
        entity.setBuyerName(text(row.get("buyerName")));
        entity.setProductTitle(text(row.get("title")));
        entity.setImageUrl(text(row.get("image")));
        Integer flag = nullableInt(row.get("buyerFlag"));
        if (flag != null) {
            entity.setBuyerFlag(flag);
        }
        entity.setSiteCode(text(row.get("siteCode")));
        entity.setRawPayload(rawPayload(row));
        entity.setSyncedAt(now);
    }

    /** 原始报文留档，便于口径回溯；领星返回结构变更时可从 raw_payload 恢复。 */
    private String rawPayload(Map<String, Object> row) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", SOURCE);
        payload.put("toolId", TOOL_ID);
        for (String key : new String[]{"id", "commentDate", "commentStar", "platformProductNo",
                "buyerName", "siteCode", "siteText", "storeId", "storeName", "buyerFlag"}) {
            Object value = row.get(key);
            if (value != null) {
                payload.put(key, value);
            }
        }
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception exception) {
            return "{\"source\":\"" + SOURCE + "\"}";
        }
    }

    // ---------------------------------------------------------------- 辅助

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return String.valueOf(number);
        }
        String result = String.valueOf(value);
        return result.isBlank() ? null : result;
    }

    private static int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static Integer nullableInt(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? number.intValue() : null;
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String compact = value.trim();
        String candidate = compact.length() >= 10 ? compact.substring(0, 10) : compact;
        try {
            return LocalDate.parse(candidate, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception exception) {
            return null;
        }
    }

    private static <T> List<List<T>> partition(List<T> values, int size) {
        List<List<T>> chunks = new ArrayList<>();
        for (int i = 0; i < values.size(); i += size) {
            chunks.add(values.subList(i, Math.min(i + size, values.size())));
        }
        return chunks;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String truncate(String message) {
        return message == null ? null : (message.length() > 2000 ? message.substring(0, 2000) : message);
    }

    // ---------------------------------------------------------------- 内部类型

    /** upsert 落库结果；包级可见以配合 {@code persist} 的单测断言。 */
    record WriteResult(int insertedCount, int updatedCount, int matchedSkuCount,
                       int unmatchedCount, BigDecimal avgStar) {
    }

    public record SyncSummary(
            LocalDate windowStart,
            LocalDate windowEnd,
            int fetchedCount,
            int insertedCount,
            int updatedCount,
            int matchedSkuCount,
            int unmatchedCount,
            int rescuedCount,
            BigDecimal avgStar,
            String status,
            String errorMessage,
            long durationMs,
            LocalDateTime finishedAt
    ) {
    }

    /** 把同步日志转成视图 DTO，避免把 JPA 实体直接暴露给接口层。 */
    public Map<String, Object> toView(WalmartCommentSyncLog log) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", log.getId());
        view.put("startedAt", log.getStartedAt());
        view.put("finishedAt", log.getFinishedAt());
        view.put("status", log.getStatus());
        view.put("source", log.getSource());
        view.put("triggeredBy", log.getTriggeredBy());
        view.put("windowStart", log.getWindowStart().toString());
        view.put("windowEnd", log.getWindowEnd().toString());
        view.put("fetchedCount", log.getFetchedCount());
        view.put("insertedCount", log.getInsertedCount());
        view.put("updatedCount", log.getUpdatedCount());
        view.put("matchedSkuCount", log.getMatchedSkuCount());
        view.put("unmatchedCount", log.getUnmatchedCount());
        view.put("avgStar", log.getAvgStar());
        view.put("durationMs", log.getDurationMs());
        view.put("errorMessage", log.getErrorMessage());
        return view;
    }
}
