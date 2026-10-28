package com.ai.walmartads.walmartad;

import com.ai.walmartads.analytics.application.CampaignStyleDiagnosisService;
import com.ai.walmartads.common.UsBusinessDate;
import com.ai.walmartads.config.LingXingProperties;
import com.ai.walmartads.importing.parser.SkuResolver;
import com.ai.walmartads.workspace.WorkspaceLingXingConfigService;
import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/** Canonical LingXing MCP synchronization for all Walmart advertising reports. */
@Service
public class WalmartAdSyncService {

    private static final Logger log = LoggerFactory.getLogger(WalmartAdSyncService.class);

    /** 广告历史回填单批推进的天数上限，避免一次调用长时间占锁并撞限流。 */
    private static final int BACKFILL_BATCH_DAYS = 1;

    /**
     * 领星广告的结算滞后天数：实测 T 与 T-1 多数情况下还没结算，返回「行在、指标全 0」。
     *
     * <p>逐小时同步只重拉 {@code usToday - 2} 这一天 —— 它才是每个整点最有可能从空壳
     * 变成真实数据的那一天。改造前每小时固定重拉 3 天（18 工具 × 3 = 54 次调用），
     * 其中 2 天必然是空壳，纯属白烧配额。
     */
    private static final int SETTLEMENT_LAG_DAYS = 2;

    /**
     * 每日一轮的归因修正窗口天数：重拉 T-4 ~ T-1。
     *
     * <p>归因是滚动回填的，已结算日的指标在之后几天还会继续变化（实测 10-23 归因在
     * 10-26 仍有修正），所以每天必须整段覆盖一次，否则老日期的广告销售会长期偏低。
     */
    private static final int DAILY_REVISION_DAYS = 4;

    private final LingXingProperties properties;
    private final LingXingAdClient client;
    private final WalmartAdStore store;
    private final WalmartAdSkuResolver skuResolver;
    private final WorkspaceLingXingConfigService workspaceConfig;
    private final CampaignStyleDiagnosisService diagnosisService;
    private final ReentrantLock syncLock = new ReentrantLock();
    private final AtomicBoolean backfillRunning = new AtomicBoolean(false);

    public WalmartAdSyncService(
            LingXingProperties properties,
            LingXingAdClient client,
            WalmartAdStore store,
            WalmartAdSkuResolver skuResolver,
            WorkspaceLingXingConfigService workspaceConfig,
            CampaignStyleDiagnosisService diagnosisService
    ) {
        this.properties = properties;
        this.client = client;
        this.store = store;
        this.skuResolver = skuResolver;
        this.workspaceConfig = workspaceConfig;
        this.diagnosisService = diagnosisService;
    }

    /** Shares the already-enabled hourly LingXing schedule used by listings and orders. */
    public void scheduledSync() {
        if (!client.configured()) {
            log.debug("LingXing MCP is not configured; skip Walmart ad synchronization");
            return;
        }
        if (!syncLock.tryLock()) {
            log.info("LingXing ad synchronization is already running; skip this hourly tick");
            return;
        }
        try {
            LocalDate target = UsBusinessDate.usToday().minusDays(SETTLEMENT_LAG_DAYS);
            syncWindowUnlocked("CRON", target, target);
        } catch (Exception exception) {
            log.error("Hourly LingXing ad synchronization failed: {}", exception.getMessage(), exception);
        } finally {
            syncLock.unlock();
        }
        // History is scheduled by the workspace orchestrator only after a successful current-data run.
    }

    /** Starts or resumes the one-time historical backfill without blocking application startup. */
    public void startInitialBackfill() {
        if (!client.configured()) {
            return;
        }
        resumeHistoricalBackfill();
    }

    /**
     * 续跑广告历史回填：从 {@code wm_ads_lx_ad_sync_log} 里最后一天 SUCCESS 的 BACKFILL 之后接着跑。
     *
     * <p>单批最多推进 {@link #BACKFILL_BATCH_DAYS} 天：一次跑完整段（7/21 至今近百天）会在
     * 中途撞限流并长时间占着同步锁，改为小批量后每个整点推进一段，被限流也只是本轮不推进。
     */
    public void resumeHistoricalBackfill() {
        try {
            LocalDate completed = store.latestSuccessfulBackfillDate();
            LocalDate start = completed == null
                    ? properties.resolvedAdBackfillStart()
                    : completed.plusDays(1);
            LocalDate today = LocalDate.now();
            if (start.isAfter(today)) {
                return;
            }
            LocalDate batchEnd = start.plusDays(BACKFILL_BATCH_DAYS - 1L);
            startBackfill(start, batchEnd.isAfter(today) ? today : batchEnd);
        } catch (DataAccessException exception) {
            // Some test and maintenance profiles deliberately start without Flyway/schema access.
            log.warn("LingXing ad history backfill was not started because the normalized schema is unavailable: {}",
                    exception.getMostSpecificCause().getMessage());
        }
    }

    /**
     * 逐小时的增量同步：只重拉「最早可能已结算」的那一天。
     *
     * <p>改造前是固定 3 天窗口（T-2 ~ T），每小时 54 次调用；T 与 T-1 在领星侧还未结算，
     * 拉回来只有空壳行会被丢弃，等于每小时白打 36 次请求。现在单轮只发 18 次，
     * 把省下的配额留给每日归因修正和初始化回填。
     */
    public SyncSummary syncNow(String triggeredBy) {
        LocalDate target = UsBusinessDate.usToday().minusDays(SETTLEMENT_LAG_DAYS);
        return syncWindow(triggeredBy, target, target);
    }

    /**
     * 每日一轮的归因修正：整段重拉最近 {@link #DAILY_REVISION_DAYS} 个已收盘业务日。
     *
     * <p>领星归因是滚动回填的，已结算日的广告销售/订单在之后几天还会继续修正；
     * 只靠逐小时增量（每次只碰 T-2）会让 T-3 以前的日子永远停在首次结算值。
     */
    public SyncSummary syncDailyRevision(String triggeredBy) {
        LocalDate end = UsBusinessDate.usToday().minusDays(1);
        LocalDate start = end.minusDays(DAILY_REVISION_DAYS - 1L);
        return syncWindow(triggeredBy, start, end);
    }

    public SyncSummary syncWindow(String triggeredBy, LocalDate start, LocalDate end) {
        validateWindow(start, end);
        if (!syncLock.tryLock()) {
            throw new IllegalStateException("LingXing ad synchronization is already running");
        }
        try {
            return syncWindowUnlocked(triggeredBy, start, end);
        } finally {
            syncLock.unlock();
        }
    }

    public BackfillStart startBackfill(LocalDate requestedStart, LocalDate requestedEnd) {
        LocalDate start = requestedStart == null ? properties.resolvedAdBackfillStart() : requestedStart;
        LocalDate end = requestedEnd == null ? LocalDate.now() : requestedEnd;
        validateWindow(start, end);
        if (!backfillRunning.compareAndSet(false, true)) {
            return new BackfillStart(false, start, end, "BACKFILL_ALREADY_RUNNING");
        }
        WorkspacePrincipal principal = WorkspaceContext.requirePrincipal();
        Thread.startVirtualThread(() -> WorkspaceContext.runAs(principal, () -> {
            syncLock.lock();
            try {
                for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                    syncWindowUnlocked("BACKFILL", date, date);
                }
            } catch (Exception exception) {
                log.error("LingXing ad history backfill stopped: {}", exception.getMessage(), exception);
            } finally {
                syncLock.unlock();
                backfillRunning.set(false);
            }
        }));
        return new BackfillStart(true, start, end, "BACKFILL_STARTED");
    }

    public Map<String, Object> latestStatus() {
        Map<String, Object> latest = new LinkedHashMap<>(store.latestLog());
        latest.put("configured", client.configured());
        latest.put("backfillRunning", backfillRunning.get());
        latest.put("schedule", properties.resolvedCron());
        latest.put("rollingWindowDays", properties.resolvedAdWindowDays());
        latest.put("attributionDays", LingXingAdClient.ATTRIBUTION_DAYS);
        latest.put("toolCount", 18);
        return latest;
    }

    private SyncSummary syncWindowUnlocked(String triggeredBy, LocalDate start, LocalDate end) {
        if (!client.configured()) {
            throw new IllegalStateException("LingXing MCP is not configured");
        }
        validateWindow(start, end);
        long started = System.currentTimeMillis();
        long logId = store.startLog(triggeredBy, start, end);
        int completedTools = 0;
        try {
            Map<String, AdReportRow> reports = new LinkedHashMap<>();
            Map<String, AdSliceRow> slices = new LinkedHashMap<>();
            Map<String, SkuResolver.ResolvedSku> skuCache = new HashMap<>();
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                LingXingAdClient.FetchedDay fetched = client.fetchDay(date);
                completedTools += fetched.tools().size();
                LocalDateTime syncedAt = LocalDateTime.now();
                for (LingXingAdClient.FetchedTool tool : fetched.tools()) {
                    if (tool.spec().reportLevel() != null) {
                        for (Map<String, Object> row : tool.rows()) {
                            AdReportRow mapped = mapReport(date, syncedAt, tool.spec(), row, skuCache);
                            reports.put(reportUniqueness(mapped), mapped);
                        }
                    } else {
                        for (Map<String, Object> row : tool.rows()) {
                            AdSliceRow mapped = mapSlice(date, syncedAt, tool.spec(), row);
                            slices.put(sliceUniqueness(mapped), mapped);
                        }
                    }
                }
            }
            List<AdReportRow> reportRows = new ArrayList<>(reports.values());
            List<AdSliceRow> sliceRows = new ArrayList<>(slices.values());
            if (!"BACKFILL".equalsIgnoreCase(triggeredBy) && reportRows.isEmpty() && sliceRows.isEmpty()) {
                throw new IllegalStateException(
                        "领星广告当前窗口返回 0 条，已按异常空结果处理并保留原广告数据");
            }

            // 同步存储层把关：领星对尚未结算的当日/昨日会返回「行在、指标全 0」的空壳结果
            // （实测约 T+2 才结算）。若照单写入，广告诊断等页面会把这些 0 值日当成真实数据，
            // 拉低 CTR / CPC / 转化率 / ROAS。这里按 report_date 剔除整天空壳，
            // 使「库里有数据」等价于「领星已结算」，读取侧取最新有数据的日期即为可信末日。
            Set<LocalDate> settledDates = settledDates(reportRows);
            List<LocalDate> shellDates = reportRows.stream()
                    .map(AdReportRow::reportDate)
                    .filter(date -> !settledDates.contains(date))
                    .distinct()
                    .toList();
            List<AdReportRow> settledReports = shellDates.isEmpty() ? reportRows
                    : reportRows.stream().filter(row -> settledDates.contains(row.reportDate())).toList();
            List<AdSliceRow> settledSlices = shellDates.isEmpty() ? sliceRows
                    : sliceRows.stream().filter(row -> settledDates.contains(row.reportDate())).toList();
            if (!shellDates.isEmpty()) {
                log.info("LingXing ad synchronization skipped unsettled (all-zero) days={}", shellDates);
            }

            if (settledReports.isEmpty() && settledSlices.isEmpty()) {
                // 整个窗口都是「行在、指标全 0」：领星尚未结算。照单写库会把 0 值当真实数据，
                // 而 BACKFILL 还会把已有日期整窗清空。两种都是事故，所以一律跳过写库，
                // 并记成 SKIPPED（等待结算），不再假报 SUCCESS 误导运营。
                long skippedDuration = System.currentTimeMillis() - started;
                String message = "领星广告窗口 " + start + "~" + end + " 尚未结算（"
                        + reportRows.size() + " 条空壳行），本轮保留原数据，等待下一轮自动重试";
                store.skipLog(logId, completedTools, message, skippedDuration);
                log.info("LingXing ad synchronization skipped unsettled window {}..{}, shellRows={}, sliceRows={}",
                        start, end, reportRows.size(), sliceRows.size());
                return new SyncSummary("SKIPPED", start, end, completedTools, 0, 0, skippedDuration);
            }

            List<Long> advertiserIds = workspaceConfig.current().advertiserIds();
            Map<WalmartAdSyncGuard.CoverageKey, Long> existingCoverage =
                    store.loadCoverage(start, end, advertiserIds);
            WalmartAdSyncGuard.validate(existingCoverage, coverageOf(settledReports, settledSlices));
            store.replaceWindow(start, end, advertiserIds, settledReports, settledSlices);
            long duration = System.currentTimeMillis() - started;
            store.completeLog(logId, completedTools, settledReports.size(), settledSlices.size(), duration);
            if (!"BACKFILL".equalsIgnoreCase(triggeredBy)) {
                WorkspacePrincipal principal = WorkspaceContext.requirePrincipal();
                Thread.startVirtualThread(() -> WorkspaceContext.runAs(principal, () -> {
                    try {
                        diagnosisService.prewarmDefaultRange();
                    } catch (Exception exception) {
                        log.warn("Advertising diagnosis cache warmup after sync failed workspaceId={}: {}",
                                principal.workspaceId(), exception.getMessage());
                    }
                }));
            }
            log.info("LingXing ad synchronization completed trigger={}, window={}..{}, tools={}, reports={}, slices={}, durationMs={}",
                    triggeredBy, start, end, completedTools, settledReports.size(), settledSlices.size(), duration);
            return new SyncSummary("SUCCESS", start, end, completedTools,
                    settledReports.size(), settledSlices.size(), duration);
        } catch (Exception exception) {
            long duration = System.currentTimeMillis() - started;
            store.failLog(logId, completedTools, exception.getMessage(), duration);
            throw exception instanceof RuntimeException runtime
                    ? runtime
                    : new IllegalStateException(exception.getMessage(), exception);
        }
    }

    /**
     * 判定哪些业务日已由领星结算出真实数据。
     *
     * <p>判据：该日 AD_ITEM 事实的曝光 / 点击 / 广告费 / 广告订单 / 归因销售任一非零。
     * 历史上存在真实零投放日（如早期仅单条活动、无消耗），因此只按「指标非零」判断，
     * 不引入行数阈值 —— 行数会随在投广告项增长而变化（实测 1085 → 1108），不适合当固定基线。
     */
    private static Set<LocalDate> settledDates(List<AdReportRow> reports) {
        Set<LocalDate> settled = new java.util.LinkedHashSet<>();
        for (AdReportRow row : reports) {
            if (row.impressions() > 0 || row.clicks() > 0
                    || row.adSpend().signum() > 0 || row.attributedOrders().signum() > 0
                    || row.attributedSales().signum() > 0) {
                settled.add(row.reportDate());
            }
        }
        return settled;
    }

    private static Map<WalmartAdSyncGuard.CoverageKey, Long> coverageOf(
            List<AdReportRow> reports,
            List<AdSliceRow> slices
    ) {
        Map<WalmartAdSyncGuard.CoverageKey, Long> coverage = new HashMap<>();
        reports.forEach(row -> coverage.merge(
                new WalmartAdSyncGuard.CoverageKey(
                        row.reportDate(), row.advertiserId(), row.adType(), "REPORT:" + row.reportLevel()),
                1L,
                Long::sum));
        slices.forEach(row -> coverage.merge(
                new WalmartAdSyncGuard.CoverageKey(
                        row.reportDate(), row.advertiserId(), row.adType(), "SLICE:" + row.sliceType()),
                1L,
                Long::sum));
        return coverage;
    }

    private AdReportRow mapReport(
            LocalDate date,
            LocalDateTime syncedAt,
            LingXingAdClient.ToolSpec spec,
            Map<String, Object> row,
            Map<String, SkuResolver.ResolvedSku> skuCache
    ) {
        long advertiserId = advertiserId(row);
        String campaignId = text(row, "campaignId");
        String adGroupId = text(row, "adGroupId", "groupId");
        String itemId = text(row, "itemId");
        String adItemId = text(row, "adItemId", "key");
        String keywordId = text(row, "keywordId", "key");
        String keywordText = text(row, "keywordName", "keywordText");
        String matchType = text(row, "matchType");
        String sku = null;
        String parentSku = null;
        if (spec.reportLevel() == LingXingAdClient.ReportLevel.AD_ITEM) {
            String sourceSku = text(row, "skuId", "sku", "sellerSku", "sellerSkuId");
            String cacheKey = itemId == null ? "sku:" + sourceSku : "item:" + itemId;
            SkuResolver.ResolvedSku resolved = skuCache.computeIfAbsent(
                    cacheKey,
                    ignored -> skuResolver.resolve(itemId, sourceSku)
            );
            sku = resolved.sku();
            parentSku = resolved.parentSku();
            if (sku == null || sku.isBlank()) {
                throw new IllegalStateException("Walmart ad Item Id cannot be mapped to SKU: " + itemId
                        + " (tool=" + spec.toolId() + ", date=" + date + ")");
            }
        }
        String entityKey = switch (spec.reportLevel()) {
            case CAMPAIGN -> key(campaignId);
            case AD_GROUP -> key(campaignId, adGroupId);
            case AD_ITEM -> key(campaignId, adGroupId, adItemId, itemId);
            case KEYWORD -> key(campaignId, adGroupId, keywordId, keywordText, matchType);
        };
        Metrics metrics = metrics(row);
        return new AdReportRow(
                date, advertiserId, spec.adType(), spec.reportLevel().name(), entityKey,
                campaignId, text(row, "campaignName"), text(row, "campaignType"),
                text(row, "campaignStatus"), targetingType(row), text(row, "biddingStrategy"),
                decimal(row, "dailyBudget"), decimal(row, "targetRoas", "currentTargetRoas"),
                adGroupId, text(row, "adGroupName", "groupName"), text(row, "adGroupStatus"),
                itemId, adItemId, sku, parentSku, text(row, "adName", "itemName"),
                text(row, "adStatus"), text(row, "reviewStatus"), text(row, "reviewReason"),
                keywordId, keywordText, text(row, "keywordState"), text(row, "keywordStatus", "status"),
                matchType, decimal(row, "keywordBid", "bid"), decimal(row, "suggestedBid"),
                metrics.impressions(), metrics.clicks(), metrics.adSpend(), metrics.attributedSales(),
                metrics.attributedOrders(), metrics.attributedUnits(), metrics.advertisedSkuSales(),
                metrics.advertisedSkuUnits(), metrics.otherSkuSales(), metrics.otherSkuUnits(),
                metrics.ntbOrders(), metrics.ntbRevenue(), metrics.ntbUnits(), metrics.cpc(),
                metrics.ctr(), metrics.cvr(), metrics.acos(), metrics.roas(), metrics.aov(), metrics.cpa(),
                syncedAt);
    }

    private AdSliceRow mapSlice(
            LocalDate date,
            LocalDateTime syncedAt,
            LingXingAdClient.ToolSpec spec,
            Map<String, Object> row
    ) {
        long advertiserId = advertiserId(row);
        String campaignId = text(row, "campaignId");
        String adGroupId = text(row, "adGroupId", "groupId");
        String dimension = spec.sliceType() == LingXingAdClient.SliceType.PLATFORM
                ? text(row, "platform")
                : text(row, "pageType", "platformType", "placementType", "type");
        if (dimension == null) {
            throw new IllegalStateException("LingXing ad slice has no dimension: " + spec.toolId());
        }
        Metrics metrics = metrics(row);
        return new AdSliceRow(
                date, advertiserId, spec.adType(), spec.sliceType().name(),
                key(campaignId, adGroupId, dimension),
                campaignId, text(row, "campaignName"), text(row, "campaignType"),
                text(row, "campaignStatus"), targetingType(row), adGroupId,
                text(row, "adGroupName", "groupName"), dimension,
                metrics.impressions(), metrics.clicks(), metrics.adSpend(), metrics.attributedSales(),
                metrics.attributedOrders(), metrics.attributedUnits(), metrics.advertisedSkuSales(),
                metrics.advertisedSkuUnits(), metrics.otherSkuSales(), metrics.otherSkuUnits(),
                metrics.ntbOrders(), metrics.ntbRevenue(), metrics.ntbUnits(), metrics.cpc(),
                metrics.ctr(), metrics.cvr(), metrics.acos(), metrics.roas(), metrics.aov(), metrics.cpa(),
                syncedAt);
    }

    private long advertiserId(Map<String, Object> row) {
        String value = text(row, "advertiserId");
        if (value != null) {
            return new BigDecimal(value).longValueExact();
        }
        List<Long> configured = workspaceConfig.current().advertiserIds();
        if (configured.size() == 1) {
            return configured.getFirst();
        }
        throw new IllegalStateException("LingXing ad row has no advertiserId while multiple advertisers are configured");
    }

    private static Metrics metrics(Map<String, Object> row) {
        return new Metrics(
                decimal(row, "numAdsShown", "impressions").longValue(),
                decimal(row, "numAdsClicks", "clicks").longValue(),
                decimal(row, "adSpend"), decimal(row, "attributedSales"),
                decimal(row, "attributedOrders"), decimal(row, "attributedUnits"),
                decimal(row, "advertisedSkuSales"), decimal(row, "advertisedSkuUnits"),
                decimal(row, "otherSkuSales"), decimal(row, "otherSkuUnits"),
                decimal(row, "ntbOrders"), decimal(row, "ntbRevenue"), decimal(row, "ntbUnits"),
                decimal(row, "cpc"), decimal(row, "ctr"), decimal(row, "cvr"),
                decimal(row, "acos"), decimal(row, "roas"), decimal(row, "aov"), decimal(row, "cpa"));
    }

    private static String targetingType(Map<String, Object> row) {
        String direct = text(row, "targetingType");
        if (direct != null) {
            return direct;
        }
        String combined = text(row, "campaignAndTargetingType");
        if (combined != null && combined.contains("+")) {
            return combined.substring(combined.lastIndexOf('+') + 1);
        }
        return null;
    }

    private static String reportUniqueness(AdReportRow row) {
        return row.reportDate() + "|" + row.advertiserId() + "|" + row.adType()
                + "|" + row.reportLevel() + "|" + row.entityKey();
    }

    private static String sliceUniqueness(AdSliceRow row) {
        return row.reportDate() + "|" + row.advertiserId() + "|" + row.adType()
                + "|" + row.sliceType() + "|" + row.entityKey();
    }

    private static String key(String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                parts.add(value.trim());
            }
        }
        if (parts.isEmpty()) {
            throw new IllegalStateException("LingXing ad row has no stable entity identifier");
        }
        String joined = String.join("|", parts);
        if (joined.length() <= 255) {
            return joined;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(digest.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String text(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private static BigDecimal decimal(Map<String, Object> row, String... keys) {
        String value = text(row, keys);
        if (value == null) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException exception) {
            return BigDecimal.ZERO;
        }
    }

    private static void validateWindow(LocalDate start, LocalDate end) {
        if (start == null || end == null || start.isAfter(end)) {
            throw new IllegalArgumentException("Invalid LingXing ad synchronization window");
        }
        if (end.isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("LingXing ad synchronization end date cannot be in the future");
        }
        if (ChronoUnit.DAYS.between(start, end) > 3650) {
            throw new IllegalArgumentException("LingXing ad synchronization window is too large");
        }
    }

    private record Metrics(
            long impressions, long clicks, BigDecimal adSpend, BigDecimal attributedSales,
            BigDecimal attributedOrders, BigDecimal attributedUnits,
            BigDecimal advertisedSkuSales, BigDecimal advertisedSkuUnits,
            BigDecimal otherSkuSales, BigDecimal otherSkuUnits,
            BigDecimal ntbOrders, BigDecimal ntbRevenue, BigDecimal ntbUnits,
            BigDecimal cpc, BigDecimal ctr, BigDecimal cvr, BigDecimal acos,
            BigDecimal roas, BigDecimal aov, BigDecimal cpa
    ) { }

    public record SyncSummary(
            String status,
            LocalDate startDate,
            LocalDate endDate,
            int completedToolCalls,
            int reportRows,
            int sliceRows,
            long durationMs
    ) { }

    public record BackfillStart(boolean started, LocalDate startDate, LocalDate endDate, String status) { }
}
