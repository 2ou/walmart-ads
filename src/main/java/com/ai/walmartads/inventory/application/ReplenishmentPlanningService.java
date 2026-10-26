package com.ai.walmartads.inventory.application;

import com.ai.walmartads.importing.parser.ImportValues;
import com.ai.walmartads.supplychain.CurrentProductionOrder;
import com.ai.walmartads.supplychain.ProductionOrderSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Loads Walmart data, calls the local forecaster, and applies the 60-day plan. */
@Service
public class ReplenishmentPlanningService {

    private static final int HORIZON_DAYS = ReplenishmentPlanner.DEFAULT_HORIZON_DAYS;
    private static final int LAST_SELLING_WINDOW_DAYS = 28;

    private final NamedParameterJdbcTemplate jdbc;
    private final StatsForecastClient forecastClient;
    private final ProductionOrderSource productionOrderSource;
    private final ParentLifecycleService lifecycleService;
    private final TransitInventoryService transitInventoryService;

    public ReplenishmentPlanningService(
            NamedParameterJdbcTemplate jdbc,
            StatsForecastClient forecastClient,
            ProductionOrderSource productionOrderSource,
            ParentLifecycleService lifecycleService,
            TransitInventoryService transitInventoryService
    ) {
        this.jdbc = jdbc;
        this.forecastClient = forecastClient;
        this.productionOrderSource = productionOrderSource;
        this.lifecycleService = lifecycleService;
        this.transitInventoryService = transitInventoryService;
    }

    public PlanningPayload plan() {
        return plan(HORIZON_DAYS);
    }

    @Transactional(readOnly = true)
    public PlanningPayload plan(int targetDays) {
        int days = Math.max(7, Math.min(targetDays, 180));
        LocalDate snapshotDate = latestWfsSnapshotDate();
        if (snapshotDate == null) {
            return PlanningPayload.empty("WFS inventory snapshot has not been imported");
        }
        List<VariantSeed> variants = loadVariants(snapshotDate);
        if (variants.isEmpty()) {
            return PlanningPayload.empty("The latest WFS inventory snapshot has no SKU rows");
        }
        Map<String, Double> domesticBySku = domesticBySku();
        Map<String, Double> trailing90Sales = salesBySku(snapshotDate.minusDays(89), snapshotDate);
        Set<String> variantSkus = variants.stream().map(VariantSeed::sku).collect(java.util.stream.Collectors.toSet());
        Map<String, Double> lastSellingPace90Sales = lastSellingPace90BySku(snapshotDate, variantSkus);
        LocalDate sameSeasonStart = snapshotDate.minusYears(1).plusDays(1);
        Map<String, Double> sameSeasonLastYearSales = salesBySku(
                sameSeasonStart,
                sameSeasonStart.plusDays(days - 1)
        );
        Map<String, List<ReplenishmentPlanner.ScheduledSupply>> incomingBySku = incomingBySku(snapshotDate, days);
        List<String> warnings = new ArrayList<>();
        Map<String, List<ReplenishmentPlanner.ScheduledSupply>> factoryWipBySku = factoryWipBySku(snapshotDate, days, warnings);
        Map<String, WipDisplay> factoryWipDisplayBySku = wipDisplayBySku(factoryWipBySku);
        factoryWipBySku.forEach((sku, supplies) -> incomingBySku.computeIfAbsent(sku, ignored -> new ArrayList<>()).addAll(supplies));

        Map<String, List<VariantSeed>> variantsByParent = new LinkedHashMap<>();
        variants.forEach(variant -> variantsByParent.computeIfAbsent(variant.parentSku(), ignored -> new ArrayList<>()).add(variant));
        Map<String, List<StatsForecastClient.Observation>> historyByParent = salesHistory(snapshotDate, variantsByParent.keySet());
        Map<String, StatsForecastClient.ForecastResult> forecastByParent = forecastClient.forecast(historyByParent, days);
        if (forecastClient.lastFailureReason() != null) {
            warnings.add("Local forecast service unavailable; baseline used: " + forecastClient.lastFailureReason());
        }
        Set<String> clearanceParents = lifecycleService.clearanceParents();
        Set<String> stockoutHistoryRecoveredSkus = new LinkedHashSet<>();

        List<VariantRecommendation> recommendations = new ArrayList<>();
        for (Map.Entry<String, List<VariantSeed>> entry : variantsByParent.entrySet()) {
            String parentSku = entry.getKey();
            List<StatsForecastClient.Observation> history = historyByParent.getOrDefault(parentSku, List.of());
            Map<String, Double> parentTrailing90Sales = demandBySku(entry.getValue(), trailing90Sales);
            Map<String, Double> parentSameSeasonSales = demandBySku(entry.getValue(), sameSeasonLastYearSales);
            Map<String, Double> parentLastSellingPace90 = demandBySku(entry.getValue(), lastSellingPace90Sales);
            Set<String> outOfStockSkus = entry.getValue().stream()
                    .filter(variant -> variant.wfsUnits() <= 0)
                    .map(VariantSeed::sku)
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
            LowSampleDemandPolicy.AllocationWeights recoveredWeights = LowSampleDemandPolicy.recoverOutOfStockWeights(
                    parentTrailing90Sales,
                    parentLastSellingPace90,
                    outOfStockSkus
            );
            Map<String, Double> recoveredHistoryFloor = new LinkedHashMap<>();
            recoveredWeights.recoveredSkus().forEach(sku ->
                    recoveredHistoryFloor.put(sku, parentLastSellingPace90.getOrDefault(sku, 0d)));
            stockoutHistoryRecoveredSkus.addAll(recoveredWeights.recoveredSkus());
            double parentTrailing90Units = parentTrailing90Sales.values().stream().mapToDouble(Double::doubleValue).sum();
            boolean lowSampleManualReview = LowSampleDemandPolicy.requiresManualReview(parentTrailing90Units);
            LowSampleDemandPolicy.Reference lowSampleReference = lowSampleManualReview
                    ? LowSampleDemandPolicy.reference(
                            days,
                            parentTrailing90Sales,
                            parentSameSeasonSales,
                            recoveredHistoryFloor
                    )
                    : null;
            StatsForecastClient.ForecastResult selectedForecast;
            Map<String, Double> allocationWeights;
            if (lowSampleManualReview) {
                selectedForecast = lowSampleReferenceForecast(days, lowSampleReference.totalDemand());
                allocationWeights = lowSampleReference.allocatedDemandBySku();
            } else {
                StatsForecastClient.ForecastResult forecast = forecastByParent.get(parentSku);
                if (forecast == null || forecast.p50().size() < days || forecast.p90().size() < days) {
                    forecast = StatsForecastClient.baseline(history, days, "BASELINE_INPUT_FALLBACK", "LOW");
                    warnings.add(parentSku + ": forecast result is incomplete; baseline was used");
                }
                selectedForecast = forecast;
                allocationWeights = recoveredWeights.weightsBySku();
            }
            List<ReplenishmentPlanner.VariantInput> plannerInputs = entry.getValue().stream()
                    .map(variant -> new ReplenishmentPlanner.VariantInput(
                            variant.sku(),
                            variant.color(),
                            variant.size(),
                            variant.cuttingYield(),
                            allocationWeights.getOrDefault(variant.sku(), 0d),
                            variant.wfsUnits(),
                            domesticBySku.getOrDefault(variant.sku(), 0d),
                            0,
                            0,
                            incomingBySku.getOrDefault(variant.sku(), List.of()),
                            recoveredWeights.recoveredSkus().contains(variant.sku())
                    ))
                    .toList();
            ReplenishmentPlanner.ParentPlan parentPlan = ReplenishmentPlanner.plan(new ReplenishmentPlanner.ParentInput(
                    parentSku,
                    snapshotDate,
                    days,
                    selectedForecast.p50(),
                    selectedForecast.p90(),
                    clearanceParents.contains(parentSku),
                    plannerInputs,
                    lowSampleManualReview
            ));
            parentPlan.variants().forEach(plan -> recommendations.add(toRecommendation(
                    parentSku,
                    selectedForecast,
                    plan,
                    factoryWipDisplayBySku.get(plan.sku())
            )));
        }
        if (!stockoutHistoryRecoveredSkus.isEmpty()) {
            warnings.add(stockoutHistoryRecoveredSkus.size()
                    + " out-of-stock SKU(s) recovered demand from their latest 28-day selling window; manual review is required");
        }
        recommendations.sort(Comparator
                .comparing(VariantRecommendation::expectedStockoutDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(VariantRecommendation::productionUnits, Comparator.reverseOrder())
                .thenComparing(VariantRecommendation::sku));
        return new PlanningPayload(
                snapshotDate,
                days,
                summary(recommendations),
                List.copyOf(warnings),
                List.copyOf(recommendations)
        );
    }

    private static Map<String, Double> demandBySku(List<VariantSeed> variants, Map<String, Double> source) {
        Map<String, Double> result = new LinkedHashMap<>();
        variants.forEach(variant -> result.put(variant.sku(), Math.max(0, source.getOrDefault(variant.sku(), 0d))));
        return result;
    }

    private static StatsForecastClient.ForecastResult lowSampleReferenceForecast(int days, double totalDemand) {
        double dailyDemand = Math.max(0, totalDemand) / days;
        List<Double> reference = new ArrayList<>(days);
        for (int index = 0; index < days; index++) {
            reference.add(dailyDemand);
        }
        return new StatsForecastClient.ForecastResult(
                List.copyOf(reference),
                List.copyOf(reference),
                "LOW_SAMPLE_SEASONAL_REFERENCE",
                "LOW",
                false,
                null,
                null
        );
    }

    private LocalDate latestWfsSnapshotDate() {
        List<Map<String, Object>> results = jdbc.queryForList("""
                select max(inventory.snapshot_at) as snapshot_at
                  from wm_ads_inventory_unified inventory
                """, new MapSqlParameterSource());
        return results.isEmpty() ? null : localDate(results.getFirst().get("snapshot_at"));
    }

    private List<VariantSeed> loadVariants(LocalDate snapshotDate) {
        return jdbc.queryForList("""
                select inventory.sku,
                       inventory.available_units,
                       product.color,
                       product.size,
                       style_yield.cutting_yield
                  from wm_ads_inventory_unified inventory
                  join (
                        select sku,
                               max(color) as color,
                               max(size) as size,
                               max(cutting_yield) as cutting_yield
                          from wm_ads_product_record
                         where current_version = true
                         group by sku
                  ) product on product.sku = inventory.sku
                  left join (
                        select coalesce(nullif(parent_sku, ''), substring_index(sku, '-', 1)) as parent_sku,
                               max(cutting_yield) as cutting_yield
                          from wm_ads_product_record
                         where current_version = true
                           and cutting_yield is not null
                         group by coalesce(nullif(parent_sku, ''), substring_index(sku, '-', 1))
                  ) style_yield on style_yield.parent_sku = substring_index(inventory.sku, '-', 1)
                 where date(inventory.snapshot_at) = :snapshotDate
                """, new MapSqlParameterSource("snapshotDate", snapshotDate)).stream()
                .map(row -> new VariantSeed(
                        text(row.get("sku")),
                        parentSku(text(row.get("sku"))),
                        text(row.get("color")),
                        text(row.get("size")),
                        integer(row.get("cutting_yield")),
                        number(row.get("available_units"))
                ))
                .filter(variant -> variant.sku() != null && variant.parentSku() != null)
                .toList();
    }

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
                    result.put(text(row.get("sku")), number(row.get("available_units"))));
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
                result.put(text(row.get("sku")), number(row.get("available_units"))));
        return result;
    }

    private Map<String, Double> salesBySku(LocalDate startDate, LocalDate endDate) {
        Map<String, Double> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                select orders.sku, sum(orders.quantity) as units
                  from wm_ads_order_record_valid orders
                  join wm_ads_import_file file on file.id = orders.import_file_id and file.active = true
                 where coalesce(orders.order_date, orders.report_date) between :startDate and :endDate
                   and (orders.status is null or (
                        lower(orders.status) not like '%cancel%'
                        and lower(orders.status) not like '%test%'
                   ))
                 group by orders.sku
                """, new MapSqlParameterSource()
                .addValue("startDate", startDate)
                .addValue("endDate", endDate)).forEach(row ->
                result.put(text(row.get("sku")), number(row.get("units"))));
        return result;
    }

    private Map<String, Double> lastSellingPace90BySku(LocalDate snapshotDate, Set<String> requestedSkus) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (requestedSkus == null || requestedSkus.isEmpty()) {
            return result;
        }
        Map<String, Map<LocalDate, Double>> dailySales = new LinkedHashMap<>();
        jdbc.queryForList("""
                select orders.sku,
                       coalesce(orders.order_date, orders.report_date) as sale_date,
                       sum(orders.quantity) as units
                 from wm_ads_order_record_valid orders
                  join wm_ads_import_file file on file.id = orders.import_file_id and file.active = true
                 where orders.sku in (:skus)
                   and coalesce(orders.order_date, orders.report_date) <= :endDate
                   and (orders.status is null or (
                        lower(orders.status) not like '%cancel%'
                        and lower(orders.status) not like '%test%'
                   ))
                 group by orders.sku, coalesce(orders.order_date, orders.report_date)
                """, new MapSqlParameterSource()
                .addValue("skus", requestedSkus)
                .addValue("endDate", snapshotDate)).forEach(row -> {
            String sku = text(row.get("sku"));
            LocalDate saleDate = localDate(row.get("sale_date"));
            double units = number(row.get("units"));
            if (sku != null && saleDate != null && units > 0) {
                dailySales.computeIfAbsent(sku, ignored -> new LinkedHashMap<>()).merge(saleDate, units, Double::sum);
            }
        });
        dailySales.forEach((sku, salesByDate) -> {
            LocalDate lastSellingDate = salesByDate.keySet().stream().max(LocalDate::compareTo).orElse(null);
            if (lastSellingDate == null) {
                return;
            }
            LocalDate windowStart = lastSellingDate.minusDays(LAST_SELLING_WINDOW_DAYS - 1L);
            double windowUnits = salesByDate.entrySet().stream()
                    .filter(entry -> !entry.getKey().isBefore(windowStart) && !entry.getKey().isAfter(lastSellingDate))
                    .mapToDouble(Map.Entry::getValue)
                    .sum();
            if (windowUnits > 0) {
                result.put(sku, windowUnits * 90d / LAST_SELLING_WINDOW_DAYS);
            }
        });
        return result;
    }

    private Map<String, List<StatsForecastClient.Observation>> salesHistory(
            LocalDate snapshotDate,
            Set<String> requestedParents
    ) {
        LocalDate startDate = snapshotDate.minusDays(364);
        Map<String, Map<LocalDate, Double>> sales = new LinkedHashMap<>();
        jdbc.queryForList("""
                select orders.sku,
                       coalesce(orders.order_date, orders.report_date) as sale_date,
                       sum(orders.quantity) as units
                  from wm_ads_order_record_valid orders
                  join wm_ads_import_file file on file.id = orders.import_file_id and file.active = true
                 where coalesce(orders.order_date, orders.report_date) between :startDate and :endDate
                   and (orders.status is null or (
                        lower(orders.status) not like '%cancel%'
                        and lower(orders.status) not like '%test%'
                   ))
                 group by orders.sku, coalesce(orders.order_date, orders.report_date)
                """, new MapSqlParameterSource()
                .addValue("startDate", startDate)
                .addValue("endDate", snapshotDate)).forEach(row -> {
            String parentSku = parentSku(text(row.get("sku")));
            LocalDate saleDate = localDate(row.get("sale_date"));
            if (requestedParents.contains(parentSku) && saleDate != null) {
                sales.computeIfAbsent(parentSku, ignored -> new LinkedHashMap<>())
                        .merge(saleDate, number(row.get("units")), Double::sum);
            }
        });
        Map<String, Set<LocalDate>> stockoutDates = parentStockoutDates(startDate, snapshotDate, requestedParents);
        Map<String, List<StatsForecastClient.Observation>> result = new LinkedHashMap<>();
        for (String parentSku : requestedParents) {
            List<StatsForecastClient.Observation> history = new ArrayList<>();
            for (LocalDate day = startDate; !day.isAfter(snapshotDate); day = day.plusDays(1)) {
                double units = sales.getOrDefault(parentSku, Map.of()).getOrDefault(day, 0d);
                boolean censored = stockoutDates.getOrDefault(parentSku, Set.of()).contains(day) && units == 0;
                history.add(new StatsForecastClient.Observation(day, censored ? null : units));
            }
            result.put(parentSku, List.copyOf(history));
        }
        return result;
    }

    private Map<String, Set<LocalDate>> parentStockoutDates(
            LocalDate startDate,
            LocalDate endDate,
            Set<String> requestedParents
    ) {
        Map<String, Set<LocalDate>> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                select inventory.sku,
                       date(inventory.snapshot_at) as snapshot_date,
                       sum(inventory.available_units) as available_units
                  from wm_ads_inventory_unified inventory
                 where date(inventory.snapshot_at) between :startDate and :endDate
                 group by inventory.sku, date(inventory.snapshot_at)
                """, new MapSqlParameterSource()
                .addValue("startDate", startDate)
                .addValue("endDate", endDate)).forEach(row -> {
            String parentSku = parentSku(text(row.get("sku")));
            LocalDate day = localDate(row.get("snapshot_date"));
            if (requestedParents.contains(parentSku) && day != null && number(row.get("available_units")) <= 0) {
                result.computeIfAbsent(parentSku, ignored -> new LinkedHashSet<>()).add(day);
            }
        });
        return result;
    }

    private Map<String, List<ReplenishmentPlanner.ScheduledSupply>> incomingBySku(LocalDate snapshotDate, int horizonDays) {
        Map<String, List<ReplenishmentPlanner.ScheduledSupply>> result = new LinkedHashMap<>();
        transitInventoryService.suppliesAvailableWithin(snapshotDate, horizonDays).forEach(supply ->
                result.computeIfAbsent(supply.sku(), ignored -> new ArrayList<>())
                        .add(new ReplenishmentPlanner.ScheduledSupply(supply.availableDay(), supply.units())));
        return result;
    }

    private Map<String, List<ReplenishmentPlanner.ScheduledSupply>> factoryWipBySku(
            LocalDate snapshotDate,
            int horizonDays,
            List<String> warnings
    ) {
        Map<String, List<ReplenishmentPlanner.ScheduledSupply>> result = new LinkedHashMap<>();
        for (CurrentProductionOrder order : productionOrderSource.currentOrders()) {
            if (order.sku() == null || order.sku().isBlank()) {
                continue;
            }
            if (order.orderDate() == null) {
                warnings.add(order.sku() + ": production order date is missing; WIP was not deducted");
                continue;
            }
            LocalDate returnDate = ImportValues.date(order.factoryReturnDate());
            LocalDate availableDate = returnDate == null ? order.orderDate().plusDays(30) : returnDate;
            long days = ChronoUnit.DAYS.between(snapshotDate, availableDate);
            if (days < 1 || days > horizonDays) {
                continue;
            }
            BigDecimal quantity = order.wipQty();
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            result.computeIfAbsent(order.sku(), ignored -> new ArrayList<>())
                    .add(new ReplenishmentPlanner.ScheduledSupply(Math.toIntExact(days), quantity.doubleValue()));
        }
        return result;
    }

    private static Map<String, WipDisplay> wipDisplayBySku(
            Map<String, List<ReplenishmentPlanner.ScheduledSupply>> suppliesBySku
    ) {
        Map<String, WipDisplay> result = new LinkedHashMap<>();
        suppliesBySku.forEach((sku, supplies) -> {
            double units = supplies.stream().mapToDouble(ReplenishmentPlanner.ScheduledSupply::units).sum();
            int availableDay = supplies.stream()
                    .mapToInt(ReplenishmentPlanner.ScheduledSupply::availableDay)
                    .min()
                    .orElse(0);
            if (units > 0 && availableDay > 0) {
                result.put(sku, new WipDisplay(units, availableDay));
            }
        });
        return result;
    }

    private static VariantRecommendation toRecommendation(
            String parentSku,
            StatsForecastClient.ForecastResult forecast,
            ReplenishmentPlanner.VariantPlan plan,
            WipDisplay factoryWip
    ) {
        return new VariantRecommendation(
                parentSku,
                plan.sku(),
                plan.color(),
                plan.size(),
                forecast.model(),
                forecast.confidence(),
                forecast.modelSelected(),
                forecast.modelWmape(),
                forecast.baselineWmape(),
                plan.demandP90Units(),
                plan.wfsUnits(),
                plan.domesticAvailableUnits(),
                plan.domesticShipmentUnits(),
                factoryWip == null ? plan.wipUnits() : factoryWip.units(),
                factoryWip == null ? plan.wipAvailableDay() : factoryWip.availableDay(),
                plan.rawProductionUnits(),
                plan.productionUnits(),
                plan.colorProductionUnits(),
                plan.cuttingSurplusUnits(),
                plan.expectedStockoutDate(),
                plan.highRiskStockoutDate(),
                plan.status().name()
        );
    }

    private static Summary summary(List<VariantRecommendation> rows) {
        return new Summary(
                round(rows.stream().mapToDouble(VariantRecommendation::domesticShipmentUnits).sum()),
                round(rows.stream().mapToDouble(VariantRecommendation::productionUnits).sum()),
                rows.stream().filter(row -> row.expectedStockoutDate() != null).count(),
                rows.stream().filter(row -> row.highRiskStockoutDate() != null).count()
        );
    }

    private static String parentSku(String sku) {
        if (sku == null || sku.isBlank()) {
            return null;
        }
        int separator = sku.indexOf('-');
        return separator > 0 ? sku.substring(0, separator) : sku;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer integer(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static double number(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.doubleValue();
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
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
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate();
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    private record VariantSeed(
            String sku,
            String parentSku,
            String color,
            String size,
            Integer cuttingYield,
            double wfsUnits
    ) {
    }

    private record WipDisplay(double units, int availableDay) {
    }

    public record PlanningPayload(
            LocalDate snapshotDate,
            int targetCoverageDays,
            Summary summary,
            List<String> warnings,
            List<VariantRecommendation> recommendations
    ) {
        static PlanningPayload empty(String warning) {
            return new PlanningPayload(null, HORIZON_DAYS, new Summary(0, 0, 0, 0), List.of(warning), List.of());
        }
    }

    public record Summary(
            double domesticShipmentUnits,
            double productionUnits,
            long expectedStockoutSkus,
            long highRiskStockoutSkus
    ) {
    }

    public record VariantRecommendation(
            String parentSku,
            String sku,
            String color,
            String size,
            String forecastModel,
            String confidence,
            boolean modelSelected,
            Double modelWmape,
            Double baselineWmape,
            double p90DemandUnits,
            double wfsUnits,
            double domesticAvailableUnits,
            double domesticShipmentUnits,
            double wipUnits,
            int wipAvailableDay,
            double rawProductionUnits,
            double productionUnits,
            double colorProductionUnits,
            double cuttingSurplusUnits,
            LocalDate expectedStockoutDate,
            LocalDate highRiskStockoutDate,
            String status
    ) {
    }
}
