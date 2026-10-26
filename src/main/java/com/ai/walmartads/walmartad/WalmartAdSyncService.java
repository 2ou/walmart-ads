package com.ai.walmartads.walmartad;

import com.ai.walmartads.analytics.application.CampaignStyleDiagnosisService;
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
            LocalDate end = LocalDate.now();
            LocalDate start = end.minusDays(properties.resolvedAdWindowDays() - 1L);
            syncWindowUnlocked("CRON", start, end);
        } catch (Exception exception) {
            log.error("Hourly LingXing ad synchronization failed: {}", exception.getMessage(), exception);
        } finally {
            syncLock.unlock();
        }
        resumeHistoricalBackfill();
    }

    /** Starts or resumes the one-time historical backfill without blocking application startup. */
    public void startInitialBackfill() {
        if (!client.configured()) {
            return;
        }
        resumeHistoricalBackfill();
    }

    private void resumeHistoricalBackfill() {
        try {
            LocalDate completed = store.latestSuccessfulBackfillDate();
            LocalDate start = completed == null
                    ? properties.resolvedAdBackfillStart()
                    : completed.plusDays(1);
            if (!start.isAfter(LocalDate.now())) {
                startBackfill(start, LocalDate.now());
            }
        } catch (DataAccessException exception) {
            // Some test and maintenance profiles deliberately start without Flyway/schema access.
            log.warn("LingXing ad history backfill was not started because the normalized schema is unavailable: {}",
                    exception.getMostSpecificCause().getMessage());
        }
    }

    public SyncSummary syncNow(String triggeredBy) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(properties.resolvedAdWindowDays() - 1L);
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
