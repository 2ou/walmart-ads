package com.ai.walmartads.inventory.application;

import com.ai.walmartads.common.WalmartImageUrl;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ai.walmartads.inventory.domain.ForecastFactor;
import com.ai.walmartads.returns.application.ReturnAnalyticsService;
import com.ai.walmartads.inventory.repository.ForecastFactorRepository;
import com.ai.walmartads.inventory.repository.ParentDailySalesOverrideRepository;
import com.ai.walmartads.supplychain.ProductionOrderSource;
import com.ai.walmartads.supplychain.SupplyChainSyncedEvent;
import com.ai.walmartads.workspace.WorkspaceContext;
import org.springframework.context.event.EventListener;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class InventoryDataService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int MIN_COMPLETE_SNAPSHOT_ROWS = 100;
    private static final double MIN_COMPLETE_SNAPSHOT_RATIO = 0.60;
    private static final long WINDOW_CACHE_NANOS = java.time.Duration.ofSeconds(20).toNanos();
    private static final long REFUND_CACHE_NANOS = java.time.Duration.ofMinutes(5).toNanos();

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductionOrderSource productionOrderSource;
    private final ForecastFactorRepository forecastFactorRepository;
    private final ParentDailySalesOverrideRepository parentDailySalesRepository;
    private final TransitInventoryService transitInventoryService;
    private final ReplenishmentPlanCache replenishmentPlanCache;
    private final ReturnAnalyticsService returnAnalyticsService;
    private final Map<WindowCacheKey, WindowCacheEntry> windowCache = new ConcurrentHashMap<>();
    private final Map<Long, RefundCacheEntry> refundCache = new ConcurrentHashMap<>();

    private record RefundCacheEntry(Map<String, Double> rateByParent, long createdAtNanos) {
    }

    @EventListener
    public void onSupplyChainSynced(SupplyChainSyncedEvent event) {
        long workspaceId = WorkspaceContext.requireId();
        windowCache.keySet().removeIf(key -> key.workspaceId() == workspaceId);
    }

    @EventListener
    public void onDomesticInventorySynced(DomesticInventorySyncedEvent event) {
        long workspaceId = WorkspaceContext.requireId();
        windowCache.keySet().removeIf(key -> key.workspaceId() == workspaceId);
    }

    @EventListener
    public void onTransitTimingChanged(TransitTimingChangedEvent event) {
        long workspaceId = WorkspaceContext.requireId();
        windowCache.keySet().removeIf(key -> key.workspaceId() == workspaceId);
    }

    public InventoryDataService(
            NamedParameterJdbcTemplate jdbc,
            ProductionOrderSource productionOrderSource,
            ForecastFactorRepository forecastFactorRepository,
            ParentDailySalesOverrideRepository parentDailySalesRepository,
            TransitInventoryService transitInventoryService,
            ReplenishmentPlanCache replenishmentPlanCache,
            ReturnAnalyticsService returnAnalyticsService
    ) {
        this.jdbc = jdbc;
        this.productionOrderSource = productionOrderSource;
        this.forecastFactorRepository = forecastFactorRepository;
        this.parentDailySalesRepository = parentDailySalesRepository;
        this.transitInventoryService = transitInventoryService;
        this.replenishmentPlanCache = replenishmentPlanCache;
        this.returnAnalyticsService = returnAnalyticsService;
    }

    @Transactional(readOnly = true)
    public InventoryPayload load(int days, int targetDays) {
        return load(days, targetDays, null, null);
    }

    @Transactional(readOnly = true)
    public InventoryPayload load(int days, int targetDays, LocalDate requestedStart, LocalDate requestedEnd) {
        return loadPayload(days, targetDays, requestedStart, requestedEnd, true);
    }

    private InventoryPayload loadPayload(
            int days,
            int targetDays,
            LocalDate requestedStart,
            LocalDate requestedEnd,
            boolean includeTrend
    ) {
        if ((requestedStart == null) != (requestedEnd == null)) {
            throw new IllegalArgumentException("自定义时间范围必须同时提供开始日期和结束日期。");
        }
        if (requestedStart != null && requestedStart.isAfter(requestedEnd)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期。");
        }
        int safeDays = requestedStart == null
                ? Math.max(1, Math.min(days, 365))
                : Math.toIntExact(ChronoUnit.DAYS.between(requestedStart, requestedEnd) + 1);
        int safeTargetDays = Math.max(7, Math.min(targetDays, 180));
        LocalDateTime snapshotAt = latestSnapshotAt();
        SalesWindow salesWindow = salesWindow(
                snapshotAt == null ? LocalDate.now() : snapshotAt.toLocalDate(),
                safeDays,
                requestedStart,
                requestedEnd
        );
        Map<String, BigDecimal> salesBySku = salesBySku(salesWindow.startDate(), salesWindow.endDate());
        Map<String, Double> rawProductionBySku = loadProductionOrderTotals();
        TransitInventoryService.TransitData transitData = transitInventoryService.load();
        Map<String, Double> rawTransitBySku = transitData.unitsBySku();
        Map<String, InventoryForecastCalculator.ForecastFactors> factorBySku = loadForecastFactors();
        Map<String, Double> parentDailySalesByParent = loadParentDailySales();
        Map<String, Double> rawDomesticBySku = domesticBySku();
        List<Map<String, Object>> productRows = jdbc.queryForList("""
                select sku, max(parent_sku) as parent_sku, max(product_id) as item_id,
                       max(product_name) as item_name, max(main_image_url) as main_image_url,
                       max(color) as color, max(size) as size, max(cutting_yield) as cutting_yield,
                       max(launch_date) as launch_date
                  from wm_ads_product_record where current_version = true group by sku
                """, new MapSqlParameterSource());
        InventorySkuMatcher matcher = new InventorySkuMatcher(productRows.stream().map(row -> text(row, "sku")).toList());
        Map<String, Double> rawSales = new LinkedHashMap<>();
        salesBySku.forEach((sku, units) -> rawSales.put(sku, decimal(units).doubleValue()));
        Map<String, Double> alignedSales = matcher.align(rawSales).matched();
        var domesticMatch = matcher.align(rawDomesticBySku);
        var productionMatch = matcher.align(rawProductionBySku);
        var transitMatch = matcher.align(rawTransitBySku);
        Map<String, Double> domesticBySku = domesticMatch.matched();
        Map<String, Double> productionBySku = productionMatch.matched();
        Map<String, Double> transitBySku = transitMatch.matched();
        double productionTotal = productionBySku.values().stream().mapToDouble(Double::doubleValue).sum();
        // 按快照索引读取 WFS，再安全映射至当前产品库；无匹配的有货记录单独展示。
        List<Map<String, Object>> inventory = jdbc.queryForList("""
                select inventory.sku,
                       inventory.parent_sku,
                       inventory.item_id,
                       inventory.item_name,
                       inventory.status,
                       inventory.daily_units_sold,
                       inventory.available_units,
                       inventory.reserved_units,
                       inventory.forecasted_days_supply,
                       inventory.out_of_stock_date,
                       inventory.suggested_units,
                       inventory.surplus_units,
                       inventory.sell_through_rate,
                       inventory.publishing_status,
                       inventory.item_lifecycle
                  from wm_ads_inventory_unified inventory
                 where inventory.snapshot_at = :snapshotAt
                 order by inventory.parent_sku, inventory.sku
                """, new MapSqlParameterSource("snapshotAt", snapshotAt));
        CatalogAlignment catalog = alignCatalog(productRows, inventory, matcher);
        Map<String, Double> unmatchedWfs = catalog.unmatchedWfs();
        inventory = catalog.rows();

        // 累计款号退款率（退款率分析口径）：按 parentSku 取；无退款数据时为 null，前端显示「—」
        Map<String, Double> refundRateByParent = loadCachedRefundRates();

        List<InventoryRow> rows = new ArrayList<>(inventory.size());
        for (Map<String, Object> source : inventory) {
            String sku = text(source, "sku");
            String parentSku = text(source, "parent_sku");
            double recentUnits = alignedSales.getOrDefault(sku, 0d);
            double reportDailyUnits = number(source, "daily_units_sold");
            double availableUnits = number(source, "available_units");
            double transitUnits = transitBySku.getOrDefault(sku, 0.0);
            double productionUnits = productionBySku.getOrDefault(sku, 0.0);
            double surplusUnits = number(source, "surplus_units");
            // 日均销量(手动)按款号维度覆盖：取该款号的手填值，套用到其下所有 SKU
            InventoryForecastCalculator.ForecastFactors baseFactors =
                    factorBySku.getOrDefault(sku, InventoryForecastCalculator.ForecastFactors.DEFAULT);
            Double parentManualDaily = parentDailySalesByParent.get(parentSku);
            InventoryForecastCalculator.ForecastFactors factors = parentManualDaily != null
                    ? new InventoryForecastCalculator.ForecastFactors(
                        baseFactors.seasonalCoef(),
                        baseFactors.gainDays(),
                        baseFactors.safetyDays(),
                        baseFactors.returnRate(),
                        baseFactors.returnReuseRate(),
                        parentManualDaily)
                    : baseFactors;
            InventoryForecastCalculator.Forecast forecast = InventoryForecastCalculator.calculate(
                    recentUnits,
                    salesWindow.sampleDays(),
                    reportDailyUnits,
                    availableUnits,
                    transitUnits,
                    productionUnits,
                    surplusUnits,
                    safeTargetDays,
                    factors
            );
            // 在时序计划计算完成前，只用当前可售库存给出保守风险，不能把未来到货当成已可售。
            String risk = styleRisk(availableUnits, availableUnits, forecast.forecastDailyUnits(),
                    forecast.forecastSuggestedUnits(), safeTargetDays);
            if (availableUnits <= 0) risk = "OUT_OF_STOCK";
            Double cumulativeRefundRate = refundRateByParent == null
                    ? null
                    : refundRateByParent.getOrDefault(parentSku, 0.0);
            rows.add(new InventoryRow(
                    sku,
                    text(source, "parent_sku"),
                    text(source, "item_id"),
                    text(source, "item_name"),
                    WalmartImageUrl.thumb(text(source, "main_image_url")),
                    text(source, "status"),
                    round(recentUnits),
                    forecast.recentDailyUnits(),
                    round(reportDailyUnits),
                    forecast.forecastDailyUnits(),
                    forecast.forecastDailyUnits() > 0,
                    round(availableUnits),
                    round(domesticBySku.getOrDefault(sku, 0d)),
                    round(number(source, "reserved_units")),
                    round(transitUnits),
                    0,
                    round(productionUnits),
                    round(number(source, "suggested_units")),
                    forecast.forecastSuggestedUnits(),
                    round(number(source, "forecasted_days_supply")),
                    forecast.forecastDailyUnits() > 0 ? round(availableUnits / forecast.forecastDailyUnits()) : null,
                    round(number(source, "sell_through_rate")),
                    localDate(source.get("out_of_stock_date")),
                    text(source, "publishing_status"),
                    text(source, "item_lifecycle"),
                    risk,
                    forecast.confidence(),
                    null,
                    null,
                    "ceil(max(0, 预测日均销量 × " + safeTargetDays + " - 可售库存 - 做货库存 - 路途库存))",
                    text(source, "color"),
                    text(source, "size"),
                    integer(source.get("cutting_yield")),
                    0,
                    0,
                    forecast.forecastSuggestedUnits() > 0
                            ? ProductionPlanAllocator.Status.MISSING_COLOR.name()
                            : ProductionPlanAllocator.Status.NOT_NEEDED.name(),
                    cumulativeRefundRate
            ));
        }
        rows = applyProductionPlans(rows);
        ReplenishmentPlanningService.PlanningPayload plan = replenishmentPlanCache.peek(safeTargetDays);
        if (plan == null) replenishmentPlanCache.refreshAsync(safeTargetDays);
        boolean planningReady = plan != null && plan.recommendations().stream()
                .map(ReplenishmentPlanningService.VariantRecommendation::sku).collect(java.util.stream.Collectors.toSet())
                .containsAll(rows.stream().map(InventoryRow::sku).toList());
        if (plan != null) rows = applyReplenishmentPlans(rows, plan);
        rows.sort(Comparator
                .comparingInt((InventoryRow row) -> riskPriority(row.risk()))
                .thenComparing(InventoryRow::forecastSuggestedUnits, Comparator.reverseOrder())
                .thenComparing(InventoryRow::sku));

        double transitTotal = rows.stream().mapToDouble(InventoryRow::transitUnits).sum();
        Summary summary = summary(rows, productionTotal, transitTotal);
        Map<String, Integer> riskCounts = new LinkedHashMap<>();
        rows.forEach(row -> riskCounts.merge(row.risk(), 1, Integer::sum));
        Meta meta = new Meta(
                snapshotAt,
                salesWindow.firstDate(),
                salesWindow.latestDate(),
                salesWindow.sampleDays(),
                safeDays,
                safeTargetDays,
                rows.size(),
                salesWindow.sampleDays() >= 7
        );
        DomesticStock domesticStock = buildDomesticStock(rows, domesticBySku);
        List<TrendRow> trend = includeTrend
                ? loadTrend(productionTotal, transitData.shipments())
                : List.of();
        List<UnmatchedStock> unmatched = new ArrayList<>();
        appendUnmatched(unmatched, "WFS", unmatchedWfs);
        appendUnmatched(unmatched, "国内仓", domesticMatch.unmatched());
        appendUnmatched(unmatched, "做货", productionMatch.unmatched());
        appendUnmatched(unmatched, "路途", transitMatch.unmatched());
        return new InventoryPayload(meta, summary, riskCounts, trend, rows, transitData, domesticStock,
                List.copyOf(unmatched), planningReady,
                replenishmentPlanCache.isRefreshing(safeTargetDays));
    }

    static CatalogAlignment alignCatalog(List<Map<String, Object>> products, List<Map<String, Object>> inventory,
                                         InventorySkuMatcher matcher) {
        Map<String, Map<String, Object>> productBySku = new LinkedHashMap<>();
        for (Map<String, Object> product : products) {
            String sku = text(product, "sku");
            if (sku == null || sku.isBlank()) continue;
            Map<String, Object> item = new LinkedHashMap<>(product);
            if (text(item, "parent_sku") == null || text(item, "parent_sku").isBlank()) {
                item.put("parent_sku", sku.split("-", 2)[0]);
            }
            productBySku.put(sku, item);
        }
        Map<String, Double> unmatched = new LinkedHashMap<>();
        for (Map<String, Object> source : inventory) {
            String sourceSku = text(source, "sku");
            String sku = matcher.resolve(sourceSku);
            if (sku == null || !productBySku.containsKey(sku)) {
                double units = number(source, "available_units");
                if (units != 0) unmatched.merge(sourceSku == null ? "（缺少 SKU）" : sourceSku, units, Double::sum);
                continue;
            }
            Map<String, Object> item = productBySku.get(sku);
            for (String field : List.of("daily_units_sold", "available_units", "reserved_units", "suggested_units", "surplus_units")) {
                item.put(field, number(item, field) + number(source, field));
            }
            for (String field : List.of("status", "forecasted_days_supply", "out_of_stock_date", "sell_through_rate", "publishing_status", "item_lifecycle")) {
                if (source.get(field) != null) item.put(field, source.get(field));
            }
        }
        return new CatalogAlignment(new ArrayList<>(productBySku.values()), Map.copyOf(unmatched));
    }

    record CatalogAlignment(List<Map<String, Object>> rows, Map<String, Double> unmatchedWfs) { }

    @Transactional(readOnly = true)
    public InventoryCoveragePayload loadCoverage(int days, int targetDays) {
        InventoryPayload payload = quickPayload(days, targetDays, null, null);
        return new InventoryCoveragePayload(payload.planningReady(),
                replenishmentPlanCache.isRefreshing(targetDays),
                payload.rows().stream().map(row -> new SkuCoverage(row.sku(), row.projectedDaysSupply(),
                        row.coverageLowerBound(), row.risk())).toList(),
                aggregateStyles(payload).stream().map(style -> new StyleCoverage(style.parentSku(),
                        style.projectedDays(), style.coverageLowerBound(), style.systemRisk())).toList());
    }

    @Transactional(readOnly = true)
    public InventoryWindowPayload loadWindow(
            int days,
            int targetDays,
            LocalDate requestedStart,
            LocalDate requestedEnd,
            int requestedPage,
            int requestedSize,
            String search,
            String risk,
            String sort,
            String direction
    ) {
        InventoryPayload payload = quickPayload(days, targetDays, requestedStart, requestedEnd);
        List<InventoryStyleSummary> styles = aggregateStyles(payload);
        String query = search == null ? "" : search.trim().toLowerCase(Locale.ROOT);
        if (!query.isEmpty()) {
            styles = styles.stream().filter(style -> style.matches(query)).toList();
        }
        if (risk != null && !risk.isBlank() && !"all".equalsIgnoreCase(risk)) {
            Set<String> risks = Set.of(risk.toUpperCase(Locale.ROOT).split(","));
            styles = styles.stream().filter(style -> risks.contains(style.systemRisk())).toList();
        }
        List<InventoryStyleSummary> ordered = new ArrayList<>(styles);
        ordered.sort(styleComparator(sort, direction));

        int size = Math.max(5, Math.min(requestedSize, 50));
        int totalElements = ordered.size();
        int totalPages = Math.max(1, (int) Math.ceil(totalElements / (double) size));
        int page = Math.max(0, Math.min(requestedPage, totalPages - 1));
        int from = Math.min(page * size, totalElements);
        int to = Math.min(from + size, totalElements);
        boolean planningReady = payload.planningReady();
        DomesticStock stock = payload.domesticStock();
        return new InventoryWindowPayload(
                payload.meta(),
                payload.summary(),
                payload.riskCounts(),
                ordered.subList(from, to),
                page,
                size,
                totalElements,
                totalPages,
                planningReady,
                replenishmentPlanCache.isRefreshing(targetDays),
                new DomesticStockSummary(stock.totalDomesticUnits(), stock.skuCount(), stock.styleCount()),
                payload.unmatchedStock()
        );
    }

    @Transactional(readOnly = true)
    public InventoryStylePayload loadStyle(
            int days,
            int targetDays,
            LocalDate requestedStart,
            LocalDate requestedEnd,
            String parentSku
    ) {
        InventoryPayload payload = quickPayload(days, targetDays, requestedStart, requestedEnd);
        String normalizedParent = parentSku == null ? "" : parentSku.trim();
        List<InventoryRow> rows = payload.rows().stream()
                .filter(row -> normalizedParent.equalsIgnoreCase(row.parentSku()))
                .toList();
        DomesticStockStyle domestic = payload.domesticStock().styles().stream()
                .filter(style -> normalizedParent.equalsIgnoreCase(style.parentSku()))
                .findFirst()
                .orElse(new DomesticStockStyle(normalizedParent, null, 0, 0, 0, List.of()));
        return new InventoryStylePayload(
                payload.meta(),
                rows,
                domestic,
                payload.planningReady(),
                replenishmentPlanCache.isRefreshing(targetDays)
        );
    }

    @Transactional(readOnly = true)
    public List<TrendRow> trend() {
        return loadTrend(0, List.of());
    }

    private InventoryPayload quickPayload(
            int days,
            int targetDays,
            LocalDate requestedStart,
            LocalDate requestedEnd
    ) {
        if ((requestedStart == null) != (requestedEnd == null)) {
            throw new IllegalArgumentException("Custom inventory range requires both startDate and endDate");
        }
        if (requestedStart != null && requestedStart.isAfter(requestedEnd)) {
            throw new IllegalArgumentException("Inventory startDate cannot be after endDate");
        }
        int safeDays = requestedStart == null
                ? Math.max(1, Math.min(days, 365))
                : Math.toIntExact(ChronoUnit.DAYS.between(requestedStart, requestedEnd) + 1);
        int safeTargetDays = Math.max(7, Math.min(targetDays, 180));
        long workspaceId = WorkspaceContext.requireId();
        WindowCacheKey key = new WindowCacheKey(workspaceId, safeDays, safeTargetDays, requestedStart, requestedEnd);
        WindowCacheEntry cached = windowCache.get(key);
        long now = System.nanoTime();
        boolean planReady = replenishmentPlanCache.peek(safeTargetDays) != null;
        if (cached != null
                && now - cached.createdAtNanos() < WINDOW_CACHE_NANOS
                && (cached.planningReady() || !planReady)) {
            return cached.payload();
        }
        InventoryPayload payload = loadPayload(safeDays, safeTargetDays, requestedStart, requestedEnd, false);
        boolean readyAfterLoad = payload.planningReady();
        windowCache.put(key, new WindowCacheEntry(payload, now, readyAfterLoad));
        windowCache.keySet().removeIf(cacheKey -> cacheKey.workspaceId() == workspaceId && !cacheKey.equals(key));
        return payload;
    }

    private static List<InventoryStyleSummary> aggregateStyles(InventoryPayload payload) {
        Map<String, Double> domesticByParent = new HashMap<>();
        payload.domesticStock().styles().forEach(style -> domesticByParent.put(style.parentSku(), style.domesticUnits()));
        Map<String, List<InventoryRow>> grouped = new LinkedHashMap<>();
        payload.rows().forEach(row -> grouped.computeIfAbsent(
                row.parentSku() == null || row.parentSku().isBlank() ? row.sku() : row.parentSku(),
                ignored -> new ArrayList<>()).add(row));
        List<InventoryStyleSummary> result = new ArrayList<>();
        grouped.forEach((parentSku, rows) -> {
            double totalAvailable = sum(rows, InventoryRow::availableUnits);
            double totalTransit = sum(rows, InventoryRow::transitUnits);
            double production = sum(rows, InventoryRow::productionUnits);
            double domestic = domesticByParent.getOrDefault(parentSku, 0.0);
            double totalStock = totalAvailable + totalTransit + production + domestic;
            double totalRecent = sum(rows, InventoryRow::recentUnits);
            double totalForecastDaily = sum(rows, InventoryRow::forecastDailyUnits);
            double forecastSuggested = sum(rows, InventoryRow::forecastSuggestedUnits);
            Double continuousDays = rows.stream().filter(InventoryRow::hasProjectedDemand)
                    .map(InventoryRow::projectedDaysSupply).filter(java.util.Objects::nonNull)
                    .min(Double::compareTo).orElse(null);
            boolean coverageLowerBound = continuousDays != null && rows.stream()
                    .filter(row -> row.hasProjectedDemand() && row.projectedDaysSupply() != null
                            && Double.compare(row.projectedDaysSupply(), continuousDays) == 0)
                    .allMatch(InventoryRow::coverageLowerBound);
            double riskDaily = totalForecastDaily > 0 ? totalForecastDaily
                    : rows.stream().anyMatch(InventoryRow::hasProjectedDemand) ? 1 : 0;
            String systemRisk = styleRisk(totalAvailable,
                    continuousDays == null ? 0 : continuousDays * riskDaily,
                    riskDaily, forecastSuggested, payload.meta().targetDays());
            InventoryRow representative = rows.getFirst();
            result.add(new InventoryStyleSummary(
                    parentSku,
                    representative.itemName(),
                    representative.itemId(),
                    representative.imageUrl(),
                    rows.size(),
                    round(totalAvailable),
                    round(totalTransit),
                    round(totalStock),
                    round(totalRecent),
                    round(totalForecastDaily),
                    round(forecastSuggested),
                    round(sum(rows, InventoryRow::productionSuggestedUnits)),
                    round(sum(rows, InventoryRow::domesticShipmentUnits)),
                    round(domestic),
                    round(production),
                    systemRisk,
                    (int) rows.stream().filter(row -> row.availableUnits() <= 0).count(),
                    continuousDays,
                    rows.stream().map(InventoryRow::cumulativeRefundRate).filter(java.util.Objects::nonNull).findFirst().orElse(null),
                    coverageLowerBound,
                    rows.stream().map(InventoryDataService::searchableRow).toList()
            ));
        });
        return result;
    }

    private static double sum(List<InventoryRow> rows, java.util.function.ToDoubleFunction<InventoryRow> mapper) {
        return rows.stream().mapToDouble(mapper).sum();
    }

    private static SearchableInventoryRow searchableRow(InventoryRow row) {
        return new SearchableInventoryRow(row.sku(), row.parentSku(), row.itemId(), row.itemName());
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

    static boolean inventorySearchMatches(String query, String sku, String parentSku, String itemId, String itemName) {
        String normalized = safeText(query).trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return true;
        String normalizedSku = safeText(sku).toLowerCase(Locale.ROOT);
        String normalizedParent = safeText(parentSku).toLowerCase(Locale.ROOT);
        String normalizedItemId = safeText(itemId).trim().toLowerCase(Locale.ROOT);
        if (normalized.matches("[0-9]+")) {
            return normalizedSku.contains(normalized) || normalizedParent.contains(normalized)
                    || normalizedItemId.equals(normalized);
        }
        List<String> fields = List.of(normalizedSku, normalizedParent, normalizedItemId,
                safeText(itemName).toLowerCase(Locale.ROOT));
        return Arrays.stream(normalized.split("\\s+"))
                .allMatch(term -> fields.stream().anyMatch(field -> field.contains(term)));
    }

    private record SearchableInventoryRow(String sku, String parentSku, String itemId, String itemName) {
        boolean matches(String query) {
            return inventorySearchMatches(query, sku, parentSku, itemId, itemName);
        }
    }

    static String styleRisk(double available, double supply, double forecastDaily, double suggested, int targetDays) {
        if (available <= 0) return "OUT_OF_STOCK";
        if (forecastDaily <= 0) return "NO_DEMAND";
        double projectedDays = supply / forecastDaily;
        if (projectedDays <= 7) return "URGENT";
        if (projectedDays <= 14) return "LOW";
        if (projectedDays <= 30) return "WATCH";
        if (projectedDays > targetDays * 1.5) return "OVERSTOCK";
        if (suggested > 0) return "REPLENISH";
        return "HEALTHY";
    }

    private static Comparator<InventoryStyleSummary> styleComparator(String requestedSort, String requestedDirection) {
        String sort = requestedSort == null ? "parentSku" : requestedSort;
        Comparator<InventoryStyleSummary> comparator = switch (sort) {
            case "risk" -> Comparator.comparingInt(style -> riskPriority(style.systemRisk()));
            case "totalStock" -> Comparator.comparingDouble(InventoryStyleSummary::totalStock);
            case "totalAvailable" -> Comparator.comparingDouble(InventoryStyleSummary::totalAvailable);
            case "domesticUnits" -> Comparator.comparingDouble(InventoryStyleSummary::domesticUnits);
            case "totalTransit" -> Comparator.comparingDouble(InventoryStyleSummary::totalTransit);
            case "totalRecent" -> Comparator.comparingDouble(InventoryStyleSummary::totalRecent);
            case "totalForecastDays" -> Comparator.comparing(
                    InventoryStyleSummary::projectedDays,
                    Comparator.nullsLast(Double::compareTo));
            case "fcSuggest" -> Comparator.comparingDouble(InventoryStyleSummary::forecastSuggestedUnits);
            case "productionSuggest" -> Comparator.comparingDouble(InventoryStyleSummary::productionSuggestedUnits);
            case "production" -> Comparator.comparingDouble(InventoryStyleSummary::productionUnits);
            case "cumulativeRefundRate" -> Comparator.comparing(
                    InventoryStyleSummary::cumulativeRefundRate,
                    Comparator.nullsLast(Double::compareTo));
            default -> Comparator.comparing(
                    InventoryStyleSummary::parentSku,
                    Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
        };
        return "desc".equalsIgnoreCase(requestedDirection) ? comparator.reversed() : comparator;
    }

    @Transactional(readOnly = true)
    public List<ProductionOrderView> productionOrders() {
        List<String> productSkus = jdbc.queryForList("""
                select distinct sku from wm_ads_product_record where current_version = true
                """, new MapSqlParameterSource(), String.class);
        InventorySkuMatcher matcher = new InventorySkuMatcher(productSkus);
        return productionOrderSource.currentOrders().stream()
                .map(record -> new ProductionOrderView(
                        record.orderNo(),
                        record.styleNo(),
                        matcher.resolve(record.sku()) == null ? record.sku() : matcher.resolve(record.sku()),
                        record.productionQty() == null ? 0 : round(record.productionQty().doubleValue()),
                        record.wipQty() == null ? 0 : round(record.wipQty().doubleValue()),
                        record.wipQty() == null || matcher.resolve(record.sku()) == null ? 0 : round(record.wipQty().doubleValue()),
                        matcher.resolve(record.sku()) != null,
                        record.plannedShipQty() == null ? null : round(record.plannedShipQty().doubleValue()),
                        record.unplannedShipQty() == null ? null : round(record.unplannedShipQty().doubleValue()),
                        record.reconciliationStatus(),
                        record.factoryReturnDate(),
                        record.source()
                ))
                .toList();
    }

    /**
     * 有效款号清单（产品库 current_version = 1）。库存口径统一以它为准，
     * 与 loadTrend / 其它模块的过滤保持一致。
     */
    private List<String> loadValidParentSkus() {
        return jdbc.queryForList("""
                select distinct product.parent_sku
                  from wm_ads_product_record product
                 where product.current_version = 1
                 order by product.parent_sku
                """, new MapSqlParameterSource(), String.class);
    }

    private LocalDateTime latestSnapshotAt() {
        List<SnapshotCandidate> candidates = jdbc.queryForList("""
                select inventory.snapshot_at, count(*) as row_count
                  from wm_ads_inventory_unified inventory
                 group by inventory.snapshot_at
                 order by inventory.snapshot_at desc
                 limit 2
                """, new MapSqlParameterSource()).stream()
                .map(row -> new SnapshotCandidate(
                        localDateTime(row.get("snapshot_at")),
                        integer(row.get("row_count"))))
                .toList();
        return selectCompleteSnapshot(candidates);
    }

    static LocalDateTime selectCompleteSnapshot(List<SnapshotCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        SnapshotCandidate latest = candidates.getFirst();
        if (candidates.size() == 1) {
            return latest.snapshotAt();
        }
        SnapshotCandidate previous = candidates.get(1);
        boolean latestIsTruncated = previous.rowCount() >= MIN_COMPLETE_SNAPSHOT_ROWS
                && latest.rowCount() < Math.ceil(previous.rowCount() * MIN_COMPLETE_SNAPSHOT_RATIO);
        return latestIsTruncated ? previous.snapshotAt() : latest.snapshotAt();
    }

    record SnapshotCandidate(LocalDateTime snapshotAt, int rowCount) {
    }

    private Map<String, Double> loadCachedRefundRates() {
        long workspaceId = WorkspaceContext.current()
                .map(com.ai.walmartads.workspace.WorkspacePrincipal::workspaceId)
                .orElse(1L);
        long now = System.nanoTime();
        RefundCacheEntry cached = refundCache.get(workspaceId);
        if (cached != null && now - cached.createdAtNanos() < REFUND_CACHE_NANOS) {
            return cached.rateByParent();
        }
        Map<String, Double> rates = returnAnalyticsService.cumulativeRefundRateByParent();
        if (rates != null) {
            refundCache.put(workspaceId, new RefundCacheEntry(rates, now));
        }
        return rates;
    }

    private SalesWindow salesWindow(
            LocalDate fallbackLatestDate,
            int days,
            LocalDate requestedStart,
            LocalDate requestedEnd
    ) {
        Map<String, Object> bounds = jdbc.queryForMap("""
                select min(coalesce(orders.order_date, orders.report_date)) as first_date,
                       max(coalesce(orders.order_date, orders.report_date)) as latest_date
                  from wm_ads_order_record_valid orders
                  join wm_ads_import_file file on file.id = orders.import_file_id and file.active = true
                """, new MapSqlParameterSource());
        LocalDate firstDate = localDate(bounds.get("first_date"));
        LocalDate latestDate = localDate(bounds.get("latest_date"));
        if (latestDate == null) {
            latestDate = fallbackLatestDate;
        }
        LocalDate startDate = requestedStart == null ? latestDate.minusDays(days - 1L) : requestedStart;
        LocalDate endDate = requestedEnd == null ? latestDate : requestedEnd;
        Integer sampleDays = jdbc.queryForObject("""
                select count(distinct coalesce(orders.order_date, orders.report_date))
                  from wm_ads_order_record_valid orders
                  join wm_ads_import_file file on file.id = orders.import_file_id and file.active = true
                 where coalesce(orders.order_date, orders.report_date) between :startDate and :endDate
                """, new MapSqlParameterSource()
                .addValue("startDate", startDate)
                .addValue("endDate", endDate), Integer.class);
        return new SalesWindow(firstDate, latestDate, startDate, endDate, sampleDays == null ? 0 : sampleDays);
    }

    private Map<String, BigDecimal> salesBySku(LocalDate startDate, LocalDate latestDate) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                select orders.sku, sum(orders.quantity) as units
                  from wm_ads_order_record_valid orders
                  join wm_ads_import_file file on file.id = orders.import_file_id and file.active = true
                 where coalesce(orders.order_date, orders.report_date) between :startDate and :latestDate
                 group by orders.sku
                """, new MapSqlParameterSource()
                .addValue("startDate", startDate)
                .addValue("latestDate", latestDate)).forEach(row ->
                result.put(text(row, "sku"), decimal(row.get("units"))));
        return result;
    }

    private Map<String, Double> loadProductionOrderTotals() {
        Map<String, Double> result = new LinkedHashMap<>();
        productionOrderSource.currentOrders().forEach(record -> {
            String sku = record.sku();
            if (sku == null) {
                return;
            }
            double qty = record.wipQty() == null ? 0 : record.wipQty().doubleValue();
            result.merge(sku, qty, Double::sum);
        });
        return result;
    }

    /**
     * 国内仓最新快照的可用数量（按 SKU 汇总）。
     * 复用 ReplenishmentPlanningService.domesticBySku 的查询口径：新鲜插件快照优先，
     * 插件过期且人工上传更新时才回退文件快照。
     */
    private Map<String, Double> domesticBySku() {
        Map<String, Double> result = new LinkedHashMap<>();
        LocalDateTime automaticAt = jdbc.queryForObject(
                "select max(synced_at) from wm_ads_lx_domestic_inventory", new MapSqlParameterSource(),
                LocalDateTime.class);
        LocalDateTime uploadAt = jdbc.queryForObject("""
                select max(domestic.snapshot_at)
                  from wm_ads_domestic_inventory_snapshot domestic
                  join wm_ads_import_file file on file.id=domestic.import_file_id and file.active=true
                """, new MapSqlParameterSource(), LocalDateTime.class);
        if (LingxingSourcePolicy.preferAutomatic(automaticAt, uploadAt)) {
            jdbc.queryForList("""
                    select sku, available_units
                      from wm_ads_lx_domestic_inventory
                    """, new MapSqlParameterSource()).forEach(row ->
                    result.put(text(row, "sku"), number(row, "available_units")));
            return result;
        }
        jdbc.queryForList("""
                select domestic.sku, sum(domestic.available_units) as available_units
                  from wm_ads_domestic_inventory_snapshot domestic
                  join wm_ads_import_file file on file.id = domestic.import_file_id and file.active = true
                 where domestic.snapshot_at = (
                        select max(current_domestic.snapshot_at)
                          from wm_ads_domestic_inventory_snapshot current_domestic
                          join wm_ads_import_file current_file
                            on current_file.id = current_domestic.import_file_id and current_file.active = true
                 )
                 group by domestic.sku
                """, new MapSqlParameterSource()).forEach(row ->
                result.put(text(row, "sku"), number(row, "available_units")));
        return result;
    }

    private Map<String, InventoryForecastCalculator.ForecastFactors> loadForecastFactors() {
        Map<String, InventoryForecastCalculator.ForecastFactors> result = new LinkedHashMap<>();
        forecastFactorRepository.findAll().forEach(factor ->
                result.put(factor.getSku(), toFactors(factor)));
        return result;
    }

    private static InventoryForecastCalculator.ForecastFactors toFactors(ForecastFactor f) {
        if (f == null) {
            return InventoryForecastCalculator.ForecastFactors.DEFAULT;
        }
        return new InventoryForecastCalculator.ForecastFactors(
                f.getSeasonalCoef() == null ? 1.0 : f.getSeasonalCoef().doubleValue(),
                f.getGainDays() == null ? 0 : f.getGainDays().doubleValue(),
                f.getSafetyDays() == null ? 0 : f.getSafetyDays().doubleValue(),
                f.getReturnRate() == null ? 0 : f.getReturnRate().doubleValue(),
                f.getReturnReuseRate() == null ? 0 : f.getReturnReuseRate().doubleValue(),
                null
        );
    }

    private Map<String, Double> loadParentDailySales() {
        Map<String, Double> result = new LinkedHashMap<>();
        parentDailySalesRepository.findAll().forEach(o -> {
            if (o.getParentSku() != null) {
                result.put(o.getParentSku(), o.getManualDailySales() == null ? null : o.getManualDailySales().doubleValue());
            }
        });
        return result;
    }

    private List<TrendRow> loadTrend(
            double productionUnits,
            List<TransitInventoryService.TransitShipment> transitShipments
    ) {
        return jdbc.queryForList("""
                with rep as (
                  select snapshot_at,
                         case when sum(source_type = 'LINGXING') > 0 then 'LINGXING'
                              when sum(source_type = 'LEGACY')   > 0 then 'LEGACY'
                              when sum(source_type = 'WEEKLY')   > 0 then 'WEEKLY'
                              else min(source_type) end as source_type
                    from wm_ads_inventory_unified
                   where parent_sku in (select p.parent_sku
                                          from wm_ads_product_record p
                                         where p.current_version = 1)
                   group by snapshot_at
                )
                select inventory.snapshot_at,
                       sum(inventory.available_units) as available_units,
                       sum(inventory.reserved_units) as reserved_units,
                       sum(inventory.suggested_units) as system_suggested_units
                  from wm_ads_inventory_unified inventory
                  join rep on rep.snapshot_at = inventory.snapshot_at
                          and rep.source_type = inventory.source_type
                 where inventory.parent_sku in (select p.parent_sku
                                                  from wm_ads_product_record p
                                                 where p.current_version = 1)
                 group by inventory.snapshot_at
                 order by inventory.snapshot_at
                """, new MapSqlParameterSource()).stream().map(row -> new TrendRow(
                        localDateTime(row.get("snapshot_at")),
                        round(number(row, "available_units")),
                        round(number(row, "reserved_units")),
                        round(transitUnitsAt(localDateTime(row.get("snapshot_at")), transitShipments)),
                        round(number(row, "system_suggested_units")),
                        round(productionUnits)
                )).toList();
    }

    private static double transitUnitsAt(
            LocalDateTime snapshotAt,
            List<TransitInventoryService.TransitShipment> transitShipments
    ) {
        if (snapshotAt == null) {
            return 0;
        }
        return transitShipments.stream()
                .filter(shipment -> shipment.createdAt() != null && !shipment.createdAt().isAfter(snapshotAt))
                .mapToDouble(TransitInventoryService.TransitShipment::units)
                .sum();
    }

    private static List<InventoryRow> applyProductionPlans(List<InventoryRow> rows) {
        List<InventoryRow> planned = new ArrayList<>(rows);
        Map<String, List<Integer>> byColor = new LinkedHashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            InventoryRow row = rows.get(index);
            String color = row.color() == null ? "" : row.color().trim();
            if (color.isEmpty()) {
                ProductionPlanAllocator.Status status = row.forecastSuggestedUnits() > 0
                        ? ProductionPlanAllocator.Status.MISSING_COLOR
                        : ProductionPlanAllocator.Status.NOT_NEEDED;
                planned.set(index, row.withProductionPlan(0, 0, status));
                continue;
            }
            String parent = row.parentSku() == null ? "" : row.parentSku().trim();
            String groupKey = parent.toLowerCase() + "\u0000" + color.toLowerCase();
            byColor.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(index);
        }

        byColor.values().forEach(indexes -> {
            List<ProductionPlanAllocator.Requirement> requirements = indexes.stream()
                    .map(index -> new ProductionPlanAllocator.Requirement(
                            String.valueOf(index),
                            rows.get(index).forecastSuggestedUnits()
                    ))
                    .toList();
            Integer cuttingYield = indexes.stream()
                    .map(index -> rows.get(index).cuttingYield())
                    .filter(value -> value != null)
                    .findFirst()
                    .orElse(null);
            ProductionPlanAllocator.Allocation allocation = ProductionPlanAllocator.allocate(
                    requirements,
                    cuttingYield
            );
            indexes.forEach(index -> planned.set(index, rows.get(index).withProductionPlan(
                    allocation.unitsByKey().getOrDefault(String.valueOf(index), 0d),
                    allocation.colorTotalUnits(),
                    allocation.status()
            )));
        });
        return planned;
    }

    private List<InventoryRow> applyReplenishmentPlans(List<InventoryRow> rows,
            ReplenishmentPlanningService.PlanningPayload payload) {
        Map<String, ReplenishmentPlanningService.VariantRecommendation> bySku = new LinkedHashMap<>();
        payload.recommendations().forEach(plan -> bySku.put(plan.sku(), plan));
        return new ArrayList<>(rows.stream()
                .map(row -> {
                    ReplenishmentPlanningService.VariantRecommendation plan = bySku.get(row.sku());
                    return plan == null ? row : row.withReplenishmentPlan(
                            plan,
                            payload.targetCoverageDays(),
                            payload.snapshotDate()
                    );
                })
                .toList());
    }

    private static Summary summary(List<InventoryRow> rows, double productionUnits, double transitUnits) {
        return new Summary(
                round(rows.stream().mapToDouble(InventoryRow::availableUnits).sum()),
                round(rows.stream().mapToDouble(InventoryRow::reservedUnits).sum()),
                round(transitUnits),
                round(rows.stream().mapToDouble(InventoryRow::productionUnits).sum()),
                round(rows.stream().mapToDouble(InventoryRow::systemSuggestedUnits).sum()),
                round(rows.stream().mapToDouble(InventoryRow::forecastSuggestedUnits).sum()),
                round(rows.stream().mapToDouble(InventoryRow::productionSuggestedUnits).sum()),
                rows.stream().filter(row -> row.risk().equals("OUT_OF_STOCK")).count(),
                rows.stream().filter(row -> List.of("URGENT", "LOW").contains(row.risk())).count()
        );
    }

    private static int riskPriority(String risk) {
        return switch (risk) {
            case "OUT_OF_STOCK" -> 0;
            case "URGENT" -> 1;
            case "LOW" -> 2;
            case "WATCH" -> 3;
            case "REPLENISH" -> 4;
            case "OVERSTOCK" -> 5;
            case "NO_DEMAND" -> 6;
            default -> 7;
        };
    }

    private static void appendUnmatched(List<UnmatchedStock> target, String source, Map<String, Double> rows) {
        rows.forEach((sku, units) -> target.add(new UnmatchedStock(source, sku, round(units))));
    }

    /**
     * 由 WFS 库存快照行 + 国内仓可用数量构建国内仓库存钻取数据。
     * 仅纳入国内仓可用 > 0 的 SKU（国内仓 = 0 视为缺货、不展示）。
     * 三级结构：款号(parentSku) → SKC(款+色) → SKU(款+色+码)，每层均按国内仓可用件数降序。
     */
    private static DomesticStock buildDomesticStock(
            List<InventoryRow> rows,
            Map<String, Double> domesticBySku
    ) {
        Map<String, List<InventoryRow>> byParent = new LinkedHashMap<>();
        for (InventoryRow row : rows) {
            double domestic = domesticBySku.getOrDefault(row.sku(), 0.0);
            if (domestic > 0) {
                String parent = (row.parentSku() == null || row.parentSku().isBlank())
                        ? row.sku()
                        : row.parentSku();
                byParent.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(row);
            }
        }
        List<DomesticStockStyle> styles = new ArrayList<>();
        long totalSkuCount = 0;
        double grandTotal = 0;
        for (Map.Entry<String, List<InventoryRow>> entry : byParent.entrySet()) {
            String parentSku = entry.getKey();
            List<InventoryRow> parentRows = entry.getValue();
            // 二级：按颜色聚合 SKC（款 + 色）
            Map<String, List<InventoryRow>> byColor = new LinkedHashMap<>();
            for (InventoryRow row : parentRows) {
                String color = row.color() == null ? "" : row.color();
                byColor.computeIfAbsent(color, ignored -> new ArrayList<>()).add(row);
            }
            List<DomesticStockSkc> skcs = new ArrayList<>();
            double styleSum = 0;
            for (Map.Entry<String, List<InventoryRow>> ce : byColor.entrySet()) {
                String color = ce.getKey();
                List<InventoryRow> colorRows = ce.getValue();
                List<DomesticStockSku> skus = new ArrayList<>();
                double skcSum = 0;
                for (InventoryRow row : colorRows) {
                    double domestic = domesticBySku.getOrDefault(row.sku(), 0.0);
                    skcSum += domestic;
                    styleSum += domestic;
                    grandTotal += domestic;
                    skus.add(new DomesticStockSku(
                            row.sku(),
                            parentSku,
                            row.color(),
                            row.size(),
                            row.imageUrl(),
                            round(domestic)
                    ));
                }
                skus.sort(Comparator.comparingDouble(DomesticStockSku::domesticUnits).reversed());
                String skcImage = colorRows.stream()
                        .map(InventoryRow::imageUrl)
                        .filter(img -> img != null && !img.isBlank())
                        .findFirst().orElse(null);
                skcs.add(new DomesticStockSkc(parentSku, color, skcImage, round(skcSum), skus.size(), skus));
            }
            skcs.sort(Comparator.comparingDouble(DomesticStockSkc::domesticUnits).reversed());
            String styleImage = parentRows.stream()
                    .map(InventoryRow::imageUrl)
                    .filter(img -> img != null && !img.isBlank())
                    .findFirst().orElse(null);
            totalSkuCount += parentRows.size();
            styles.add(new DomesticStockStyle(
                    parentSku, styleImage, round(styleSum), parentRows.size(), skcs.size(), skcs));
        }
        styles.sort(Comparator.comparingDouble(DomesticStockStyle::domesticUnits).reversed());
        return new DomesticStock(Math.round(grandTotal), totalSkuCount, styles.size(), styles);
    }

    private static InventoryPayload emptyPayload(int days, int targetDays) {
        return new InventoryPayload(
                new Meta(null, null, null, 0, days, targetDays, 0, false),
                new Summary(0, 0, 0, 0, 0, 0, 0, 0, 0),
                Map.of(),
                List.of(),
                List.of(),
                new TransitInventoryService.TransitData(Map.of(), List.of(), List.of(), List.of()),
                new DomesticStock(0, 0, 0, List.of()),
                List.of(), false, false
        );
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static double number(Map<String, Object> row, String key) {
        return decimal(row.get(key)).doubleValue();
    }

    private static Integer integer(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : Integer.valueOf(text);
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static LocalDate localDate(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private static LocalDateTime localDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(value).replace(" ", "T"));
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    public record InventoryPayload(
            Meta meta,
            Summary summary,
            Map<String, Integer> riskCounts,
            List<TrendRow> trend,
            List<InventoryRow> rows,
            TransitInventoryService.TransitData transit,
            DomesticStock domesticStock,
            List<UnmatchedStock> unmatchedStock,
            boolean planningReady,
            boolean planningRefreshing
    ) {
    }

    public record InventoryCoveragePayload(boolean planningReady, boolean planningRefreshing,
                                           List<SkuCoverage> skus, List<StyleCoverage> styles) { }
    public record SkuCoverage(String sku, Double projectedDays, boolean coverageLowerBound, String risk) { }
    public record StyleCoverage(String parentSku, Double projectedDays, boolean coverageLowerBound, String risk) { }

    public record InventoryWindowPayload(
            Meta meta,
            Summary summary,
            Map<String, Integer> riskCounts,
            List<InventoryStyleSummary> styles,
            int page,
            int size,
            int totalElements,
            int totalPages,
            boolean planningReady,
            boolean planningRefreshing,
            DomesticStockSummary domesticStock,
            List<UnmatchedStock> unmatchedStock
    ) {
    }

    public record InventoryStylePayload(
            Meta meta,
            List<InventoryRow> rows,
            DomesticStockStyle domesticStock,
            boolean planningReady,
            boolean planningRefreshing
    ) {
    }

    public record DomesticStockSummary(long totalDomesticUnits, long skuCount, long styleCount) {
    }

    public record UnmatchedStock(String source, String sku, double units) { }

    public record InventoryStyleSummary(
            String parentSku,
            String itemName,
            String itemId,
            String imageUrl,
            int skuCount,
            double totalAvailable,
            double totalTransit,
            double totalStock,
            double totalRecent,
            double totalForecastDaily,
            double forecastSuggestedUnits,
            double productionSuggestedUnits,
            double domesticShipmentUnits,
            double domesticUnits,
            double productionUnits,
            String systemRisk,
            int outOfStockSkuCount,
            Double projectedDays,
            Double cumulativeRefundRate,
            boolean coverageLowerBound,
            @JsonIgnore List<SearchableInventoryRow> searchableRows
    ) {
        boolean matches(String query) {
            return searchableRows.stream().anyMatch(row -> row.matches(query));
        }
    }

    private record WindowCacheKey(
            long workspaceId,
            int days,
            int targetDays,
            LocalDate startDate,
            LocalDate endDate
    ) {
    }

    private record WindowCacheEntry(InventoryPayload payload, long createdAtNanos, boolean planningReady) {
    }

    /**
     * 国内仓库存钻取数据：所有国内仓可用 > 0 的 SKU（= 0 视为缺货、不展示）。
     * 三级结构：款号 → SKC(款+色) → SKU(款+色+码)，每层按国内仓可用件数降序。
     */
    public record DomesticStock(
            long totalDomesticUnits,
            long skuCount,
            long styleCount,
            List<DomesticStockStyle> styles
    ) {
    }

    public record DomesticStockStyle(
            String parentSku,
            String imageUrl,
            double domesticUnits,
            int skuCount,
            int skcCount,
            List<DomesticStockSkc> skcs
    ) {
    }

    public record DomesticStockSkc(
            String parentSku,
            String color,
            String imageUrl,
            double domesticUnits,
            int skuCount,
            List<DomesticStockSku> skus
    ) {
    }

    public record DomesticStockSku(
            String sku,
            String parentSku,
            String color,
            String size,
            String imageUrl,
            double domesticUnits
    ) {
    }

    public record Meta(
            LocalDateTime snapshotAt,
            LocalDate firstSalesDate,
            LocalDate latestSalesDate,
            int sampleDays,
            int analysisDays,
            int targetDays,
            int skuCount,
            boolean reliableSample
    ) {
    }

    public record Summary(
            double availableUnits,
            double reservedUnits,
            double transitUnits,
            double productionUnits,
            double systemSuggestedUnits,
            double forecastSuggestedUnits,
            double productionSuggestedUnits,
            long outOfStockSkus,
            long lowStockSkus
    ) {
    }

    public record InventoryRow(
            String sku,
            String parentSku,
            String itemId,
            String itemName,
            String imageUrl,
            String status,
            double recentUnits,
            double recentDailyUnits,
            double reportDailyUnits,
            double forecastDailyUnits,
            boolean hasProjectedDemand,
            double availableUnits,
            double domesticUnits,
            double reservedUnits,
            double transitUnits,
            double domesticShipmentUnits,
            double productionUnits,
            double systemSuggestedUnits,
            double forecastSuggestedUnits,
            double systemDaysSupply,
            Double projectedDaysSupply,
            double sellThroughRate,
            LocalDate outOfStockDate,
            String publishingStatus,
            String itemLifecycle,
            String risk,
            String confidence,
            LocalDateTime productionUpdatedAt,
            String productionNote,
            String formula,
            String color,
            String size,
            Integer cuttingYield,
            double productionSuggestedUnits,
            double colorProductionSuggestedUnits,
            String productionSuggestionStatus,
            Double cumulativeRefundRate
    ) {
        InventoryRow withProductionPlan(
                double skuProductionSuggestedUnits,
                double colorProductionSuggestedUnits,
                ProductionPlanAllocator.Status status
        ) {
            return new InventoryRow(
                    sku,
                    parentSku,
                    itemId,
                    itemName,
                    imageUrl,
                    this.status,
                    recentUnits,
                    recentDailyUnits,
                    reportDailyUnits,
                    forecastDailyUnits,
                    hasProjectedDemand,
                    availableUnits,
                    domesticUnits,
                    reservedUnits,
                    transitUnits,
                    domesticShipmentUnits,
                    productionUnits,
                    systemSuggestedUnits,
                    forecastSuggestedUnits,
                    systemDaysSupply,
                    projectedDaysSupply,
                    sellThroughRate,
                    outOfStockDate,
                    publishingStatus,
                    itemLifecycle,
                    risk,
                    confidence,
                    productionUpdatedAt,
                    productionNote,
                    formula,
                    color,
                    size,
                    cuttingYield,
                    round(skuProductionSuggestedUnits),
                    round(colorProductionSuggestedUnits),
                    status.name(),
                    cumulativeRefundRate
            );
        }

        InventoryRow withReplenishmentPlan(
                ReplenishmentPlanningService.VariantRecommendation plan,
                int targetDays,
                LocalDate snapshotDate
        ) {
            String note = "P90 " + targetDays + "d=" + round(plan.p90DemandUnits())
                    + ", domestic ship=" + round(plan.domesticShipmentUnits())
                    + ", factory WIP=" + round(plan.wipUnits())
                    + ", model=" + plan.forecastModel();
            String formula = "P90 demand is covered by WFS, domestic shipment, transit and eligible factory WIP on their arrival dates; "
                    + "transit is not counted again as factory WIP";
            LocalDate base = snapshotDate == null ? LocalDate.now() : snapshotDate;
            Double continuousDays = null;
            if (plan.expectedStockoutDate() != null) {
                continuousDays = (double) Math.max(0, ChronoUnit.DAYS.between(base, plan.expectedStockoutDate()));
            } else if (plan.p90DemandUnits() > 0) {
                continuousDays = (double) targetDays;
            }
            if (availableUnits <= 0) continuousDays = 0d;
            return new InventoryRow(
                    sku,
                    parentSku,
                    itemId,
                    itemName,
                    imageUrl,
                    status,
                    recentUnits,
                    recentDailyUnits,
                    reportDailyUnits,
                    forecastDailyUnits,
                    plan.p90DemandUnits() > 0,
                    availableUnits,
                    domesticUnits,
                    reservedUnits,
                    transitUnits,
                    round(plan.domesticShipmentUnits()),
                    productionUnits,
                    systemSuggestedUnits,
                    round(plan.rawProductionUnits()),
                    systemDaysSupply,
                    continuousDays,
                    sellThroughRate,
                    plan.expectedStockoutDate(),
                    publishingStatus,
                    itemLifecycle,
                    availableUnits <= 0 ? "OUT_OF_STOCK" : replenishmentRisk(plan, snapshotDate),
                    plan.confidence() == null ? confidence : plan.confidence(),
                    productionUpdatedAt,
                    note,
                    formula,
                    color,
                    size,
                    cuttingYield,
                    round(plan.productionUnits()),
                    round(plan.colorProductionUnits()),
                    plan.status(),
                    cumulativeRefundRate
            );
        }

        private static String replenishmentRisk(
                ReplenishmentPlanningService.VariantRecommendation plan,
                LocalDate snapshotDate
        ) {
            if (plan.expectedStockoutDate() == null) {
                if (plan.p90DemandUnits() <= 0) return "NO_DEMAND";
                return plan.rawProductionUnits() > 0 ? "REPLENISH" : "HEALTHY";
            }
            LocalDate today = snapshotDate == null ? LocalDate.now() : snapshotDate;
            if (!plan.expectedStockoutDate().isAfter(today.plusDays(7))) {
                return "URGENT";
            }
            if (!plan.expectedStockoutDate().isAfter(today.plusDays(14))) {
                return "LOW";
            }
            if (!plan.expectedStockoutDate().isAfter(today.plusDays(ReplenishmentPlanner.DEFAULT_HORIZON_DAYS))) {
                return "WATCH";
            }
            return plan.rawProductionUnits() > 0 ? "REPLENISH" : "HEALTHY";
        }

        @com.fasterxml.jackson.annotation.JsonProperty("coverageLowerBound")
        public boolean coverageLowerBound() {
            return productionNote != null && productionNote.startsWith("P90 ")
                    && outOfStockDate == null && projectedDaysSupply != null && availableUnits > 0;
        }
    }

    public record TrendRow(
            LocalDateTime snapshotAt,
            double availableUnits,
            double reservedUnits,
            double transitUnits,
            double systemSuggestedUnits,
            double productionUnits
    ) {
    }

    public record ProductionOrderView(
            String orderNo,
            String styleNo,
            String sku,
            double productionQty,
            double unfinishedQty,
            double inventoryQty,
            boolean includedInInventory,
            Double plannedShipQty,
            Double unplannedShipQty,
            String reconciliationStatus,
            String shipDate,
            String source
    ) {
    }

    private record SalesWindow(
            LocalDate firstDate,
            LocalDate latestDate,
            LocalDate startDate,
            LocalDate endDate,
            int sampleDays
    ) {
    }
}
