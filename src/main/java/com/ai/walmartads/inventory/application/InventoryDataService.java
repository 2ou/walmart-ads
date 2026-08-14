package com.ai.walmartads.inventory.application;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ai.walmartads.importing.repository.ProductionOrderRepository;
import com.ai.walmartads.inventory.domain.ForecastFactor;
import com.ai.walmartads.inventory.repository.ForecastFactorRepository;
import com.ai.walmartads.inventory.repository.ParentDailySalesOverrideRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InventoryDataService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductionOrderRepository productionOrderRepository;
    private final ForecastFactorRepository forecastFactorRepository;
    private final ParentDailySalesOverrideRepository parentDailySalesRepository;
    private final TransitInventoryService transitInventoryService;
    private final ReplenishmentPlanCache replenishmentPlanCache;

    public InventoryDataService(
            NamedParameterJdbcTemplate jdbc,
            ProductionOrderRepository productionOrderRepository,
            ForecastFactorRepository forecastFactorRepository,
            ParentDailySalesOverrideRepository parentDailySalesRepository,
            TransitInventoryService transitInventoryService,
            ReplenishmentPlanCache replenishmentPlanCache
    ) {
        this.jdbc = jdbc;
        this.productionOrderRepository = productionOrderRepository;
        this.forecastFactorRepository = forecastFactorRepository;
        this.parentDailySalesRepository = parentDailySalesRepository;
        this.transitInventoryService = transitInventoryService;
        this.replenishmentPlanCache = replenishmentPlanCache;
    }

    @Transactional(readOnly = true)
    public InventoryPayload load(int days, int targetDays) {
        return load(days, targetDays, null, null);
    }

    @Transactional(readOnly = true)
    public InventoryPayload load(int days, int targetDays, LocalDate requestedStart, LocalDate requestedEnd) {
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
        if (snapshotAt == null) {
            return emptyPayload(safeDays, safeTargetDays);
        }

        SalesWindow salesWindow = salesWindow(
                snapshotAt.toLocalDate(),
                safeDays,
                requestedStart,
                requestedEnd
        );
        Map<String, BigDecimal> salesBySku = salesBySku(salesWindow.startDate(), salesWindow.endDate());
        Map<String, Double> productionBySku = loadProductionOrderTotals();
        double productionTotal = productionBySku.values().stream().mapToDouble(Double::doubleValue).sum();
        TransitInventoryService.TransitData transitData = transitInventoryService.load();
        Map<String, Double> transitBySku = transitData.unitsBySku();
        Map<String, InventoryForecastCalculator.ForecastFactors> factorBySku = loadForecastFactors();
        Map<String, Double> parentDailySalesByParent = loadParentDailySales();
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
                       inventory.item_lifecycle,
                       product.main_image_url,
                       product.color,
                       product.size,
                       product.cutting_yield,
                       product.launch_date
                  from wm_ads_inventory_snapshot inventory
                  join wm_ads_import_file inventory_file
                    on inventory_file.id = inventory.import_file_id
                   and inventory_file.active = true
                  left join (
                        select sku,
                               max(main_image_url) as main_image_url,
                               max(color) as color,
                               max(size) as size,
                               max(cutting_yield) as cutting_yield,
                               max(launch_date) as launch_date
                          from wm_ads_product_record
                         where current_version = true
                         group by sku
                  ) product on product.sku = inventory.sku
                 where inventory.snapshot_at = :snapshotAt
                 order by inventory.parent_sku, inventory.sku
                """, new MapSqlParameterSource("snapshotAt", snapshotAt));

        List<InventoryRow> rows = new ArrayList<>(inventory.size());
        for (Map<String, Object> source : inventory) {
            String sku = text(source, "sku");
            String parentSku = text(source, "parent_sku");
            double recentUnits = decimal(salesBySku.get(sku)).doubleValue();
            double reportDailyUnits = number(source, "daily_units_sold");
            double availableUnits = number(source, "available_units");
            double transitUnits = transitBySku.getOrDefault(sku, 0.0);
            double productionUnits = productionBySku.getOrDefault(sku, 0.0);
            double surplusUnits = number(source, "surplus_units");
            LocalDate launchDate = localDate(source.get("launch_date"));
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
            String risk = forecast.risk();
            if (launchDate == null && "OUT_OF_STOCK".equals(risk)) {
                risk = "NOT_LAUNCHED";
            }
            rows.add(new InventoryRow(
                    sku,
                    text(source, "parent_sku"),
                    text(source, "item_id"),
                    text(source, "item_name"),
                    text(source, "main_image_url"),
                    text(source, "status"),
                    round(recentUnits),
                    forecast.recentDailyUnits(),
                    round(reportDailyUnits),
                    forecast.forecastDailyUnits(),
                    round(availableUnits),
                    round(number(source, "reserved_units")),
                    round(transitUnits),
                    0,
                    round(productionUnits),
                    round(number(source, "suggested_units")),
                    forecast.forecastSuggestedUnits(),
                    round(number(source, "forecasted_days_supply")),
                    forecast.projectedDaysSupply(),
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
                            : ProductionPlanAllocator.Status.NOT_NEEDED.name()
            ));
        }
        rows = applyProductionPlans(rows);
        rows = applyReplenishmentPlans(rows);
        rows.sort(Comparator
                .comparingInt((InventoryRow row) -> riskPriority(row.risk()))
                .thenComparing(InventoryRow::forecastSuggestedUnits, Comparator.reverseOrder())
                .thenComparing(InventoryRow::sku));

        double transitTotal = transitData.shipments().stream()
                .mapToDouble(TransitInventoryService.TransitShipment::units)
                .sum();
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
        return new InventoryPayload(meta, summary, riskCounts, loadTrend(productionTotal, transitData.shipments()), rows, transitData);
    }

    @Transactional(readOnly = true)
    public List<ProductionOrderView> productionOrders() {
        return productionOrderRepository.findCurrentVersions().stream()
                .map(record -> new ProductionOrderView(
                        record.getOrderNo(),
                        record.getStyleNo(),
                        record.getSku(),
                        record.getProductionQty() == null ? 0 : round(record.getProductionQty().doubleValue()),
                        record.getFactoryReturnDate()
                ))
                .toList();
    }

    private LocalDateTime latestSnapshotAt() {
        return jdbc.queryForList("""
                select max(inventory.snapshot_at) as snapshot_at
                  from wm_ads_inventory_snapshot inventory
                  join wm_ads_import_file file on file.id = inventory.import_file_id and file.active = true
                """, new MapSqlParameterSource()).stream()
                .map(row -> localDateTime(row.get("snapshot_at")))
                .findFirst()
                .orElse(null);
    }

    private SalesWindow salesWindow(
            LocalDate fallbackLatestDate,
            int days,
            LocalDate requestedStart,
            LocalDate requestedEnd
    ) {
        Map<String, Object> bounds = jdbc.queryForMap("""
                select min(orders.report_date) as first_date,
                       max(orders.report_date) as latest_date
                  from wm_ads_order_record orders
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
                select count(distinct orders.report_date)
                  from wm_ads_order_record orders
                  join wm_ads_import_file file on file.id = orders.import_file_id and file.active = true
                 where orders.report_date between :startDate and :endDate
                """, new MapSqlParameterSource()
                .addValue("startDate", startDate)
                .addValue("endDate", endDate), Integer.class);
        return new SalesWindow(firstDate, latestDate, startDate, endDate, sampleDays == null ? 0 : sampleDays);
    }

    private Map<String, BigDecimal> salesBySku(LocalDate startDate, LocalDate latestDate) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                select orders.sku, sum(orders.quantity) as units
                  from wm_ads_order_record orders
                  join wm_ads_import_file file on file.id = orders.import_file_id and file.active = true
                 where orders.report_date between :startDate and :latestDate
                 group by orders.sku
                """, new MapSqlParameterSource()
                .addValue("startDate", startDate)
                .addValue("latestDate", latestDate)).forEach(row ->
                result.put(text(row, "sku"), decimal(row.get("units"))));
        return result;
    }

    private Map<String, Double> loadProductionOrderTotals() {
        Map<String, Double> result = new LinkedHashMap<>();
        productionOrderRepository.findCurrentVersions().forEach(record -> {
            String sku = record.getSku();
            if (sku == null) {
                return;
            }
            double qty = record.getProductionQty() == null ? 0 : record.getProductionQty().doubleValue();
            result.merge(sku, qty, Double::sum);
        });
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
                  select s.snapshot_at,
                         case when sum(case when f.file_type = 'INVENTORY' then 1 else 0 end) > 0
                              then 'INVENTORY' else 'WEEKLY_INVENTORY' end as rep_type
                    from wm_ads_inventory_snapshot s
                    join wm_ads_import_file f on f.id = s.import_file_id and f.active = true
                   group by s.snapshot_at
                )
                select inventory.snapshot_at,
                       sum(inventory.available_units) as available_units,
                       sum(inventory.reserved_units) as reserved_units,
                       sum(inventory.suggested_units) as system_suggested_units
                  from wm_ads_inventory_snapshot inventory
                  join wm_ads_import_file file on file.id = inventory.import_file_id and file.active = true
                  join rep on rep.snapshot_at = inventory.snapshot_at and rep.rep_type = file.file_type
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

    private List<InventoryRow> applyReplenishmentPlans(List<InventoryRow> rows) {
        ReplenishmentPlanningService.PlanningPayload payload = replenishmentPlanCache.current();
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

    private static InventoryPayload emptyPayload(int days, int targetDays) {
        return new InventoryPayload(
                new Meta(null, null, null, 0, days, targetDays, 0, false),
                new Summary(0, 0, 0, 0, 0, 0, 0, 0, 0),
                Map.of(),
                List.of(),
                List.of(),
                new TransitInventoryService.TransitData(Map.of(), List.of(), List.of())
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
            TransitInventoryService.TransitData transit
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
            double availableUnits,
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
            String productionSuggestionStatus
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
                    availableUnits,
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
                    status.name()
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
                    availableUnits,
                    reservedUnits,
                    transitUnits,
                    round(plan.domesticShipmentUnits()),
                    productionUnits,
                    systemSuggestedUnits,
                    round(plan.rawProductionUnits()),
                    systemDaysSupply,
                    projectedDaysSupply,
                    sellThroughRate,
                    plan.expectedStockoutDate(),
                    publishingStatus,
                    itemLifecycle,
                    replenishmentRisk(plan, risk, snapshotDate),
                    plan.confidence() == null ? confidence : plan.confidence(),
                    productionUpdatedAt,
                    note,
                    formula,
                    color,
                    size,
                    cuttingYield,
                    round(plan.productionUnits()),
                    round(plan.colorProductionUnits()),
                    plan.status()
            );
        }

        private static String replenishmentRisk(
                ReplenishmentPlanningService.VariantRecommendation plan,
                String fallback,
                LocalDate snapshotDate
        ) {
            if (plan.expectedStockoutDate() == null) {
                return fallback;
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
            return fallback;
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
            String shipDate
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
