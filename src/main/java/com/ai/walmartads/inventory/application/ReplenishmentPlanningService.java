package com.ai.walmartads.inventory.application;

import com.ai.walmartads.importing.domain.ProductionOrderRecord;
import com.ai.walmartads.importing.parser.ImportValues;
import com.ai.walmartads.importing.repository.ProductionOrderRepository;
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

    private final NamedParameterJdbcTemplate jdbc;
    private final StatsForecastClient forecastClient;
    private final ProductionOrderRepository productionOrderRepository;
    private final ParentLifecycleService lifecycleService;
    private final TransitInventoryService transitInventoryService;

    public ReplenishmentPlanningService(
            NamedParameterJdbcTemplate jdbc,
            StatsForecastClient forecastClient,
            ProductionOrderRepository productionOrderRepository,
            ParentLifecycleService lifecycleService,
            TransitInventoryService transitInventoryService
    ) {
        this.jdbc = jdbc;
        this.forecastClient = forecastClient;
        this.productionOrderRepository = productionOrderRepository;
        this.lifecycleService = lifecycleService;
        this.transitInventoryService = transitInventoryService;
    }

    @Transactional(readOnly = true)
    public PlanningPayload plan() {
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
        Map<String, List<ReplenishmentPlanner.ScheduledSupply>> incomingBySku = incomingBySku(snapshotDate);
        List<String> warnings = new ArrayList<>();
        Map<String, List<ReplenishmentPlanner.ScheduledSupply>> factoryWipBySku = factoryWipBySku(snapshotDate, warnings);
        Map<String, WipDisplay> factoryWipDisplayBySku = wipDisplayBySku(factoryWipBySku);
        factoryWipBySku.forEach((sku, supplies) -> incomingBySku.computeIfAbsent(sku, ignored -> new ArrayList<>()).addAll(supplies));

        Map<String, List<VariantSeed>> variantsByParent = new LinkedHashMap<>();
        variants.forEach(variant -> variantsByParent.computeIfAbsent(variant.parentSku(), ignored -> new ArrayList<>()).add(variant));
        Map<String, List<StatsForecastClient.Observation>> historyByParent = salesHistory(snapshotDate, variantsByParent.keySet());
        Map<String, StatsForecastClient.ForecastResult> forecastByParent = forecastClient.forecast(historyByParent, HORIZON_DAYS);
        if (forecastClient.lastFailureReason() != null) {
            warnings.add("Local forecast service unavailable; baseline used: " + forecastClient.lastFailureReason());
        }
        Set<String> clearanceParents = lifecycleService.clearanceParents();

        List<VariantRecommendation> recommendations = new ArrayList<>();
        for (Map.Entry<String, List<VariantSeed>> entry : variantsByParent.entrySet()) {
            String parentSku = entry.getKey();
            List<StatsForecastClient.Observation> history = historyByParent.getOrDefault(parentSku, List.of());
            StatsForecastClient.ForecastResult forecast = forecastByParent.get(parentSku);
            if (forecast == null || forecast.p50().size() < HORIZON_DAYS || forecast.p90().size() < HORIZON_DAYS) {
                forecast = StatsForecastClient.baseline(history, HORIZON_DAYS, "BASELINE_INPUT_FALLBACK", "LOW");
                warnings.add(parentSku + ": forecast result is incomplete; baseline was used");
            }
            StatsForecastClient.ForecastResult selectedForecast = forecast;
            List<ReplenishmentPlanner.VariantInput> plannerInputs = entry.getValue().stream()
                    .map(variant -> new ReplenishmentPlanner.VariantInput(
                            variant.sku(),
                            variant.color(),
                            variant.size(),
                            variant.cuttingYield(),
                            trailing90Sales.getOrDefault(variant.sku(), 0d),
                            variant.wfsUnits(),
                            domesticBySku.getOrDefault(variant.sku(), 0d),
                            0,
                            0,
                            incomingBySku.getOrDefault(variant.sku(), List.of())
                    ))
                    .toList();
            ReplenishmentPlanner.ParentPlan parentPlan = ReplenishmentPlanner.plan(new ReplenishmentPlanner.ParentInput(
                    parentSku,
                    snapshotDate,
                    HORIZON_DAYS,
                    selectedForecast.p50(),
                    selectedForecast.p90(),
                    clearanceParents.contains(parentSku),
                    plannerInputs
            ));
            parentPlan.variants().forEach(plan -> recommendations.add(toRecommendation(
                    parentSku,
                    selectedForecast,
                    plan,
                    factoryWipDisplayBySku.get(plan.sku())
            )));
        }
        recommendations.sort(Comparator
                .comparing(VariantRecommendation::expectedStockoutDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(VariantRecommendation::productionUnits, Comparator.reverseOrder())
                .thenComparing(VariantRecommendation::sku));
        return new PlanningPayload(
                snapshotDate,
                HORIZON_DAYS,
                summary(recommendations),
                List.copyOf(warnings),
                List.copyOf(recommendations)
        );
    }

    private LocalDate latestWfsSnapshotDate() {
        List<Map<String, Object>> results = jdbc.queryForList("""
                select max(inventory.snapshot_at) as snapshot_at
                  from wm_ads_inventory_snapshot inventory
                  join wm_ads_import_file file on file.id = inventory.import_file_id and file.active = true
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
                  from wm_ads_inventory_snapshot inventory
                  join wm_ads_import_file inventory_file
                    on inventory_file.id = inventory.import_file_id and inventory_file.active = true
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
                  from wm_ads_order_record orders
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
                  from wm_ads_order_record orders
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
                  from wm_ads_inventory_snapshot inventory
                  join wm_ads_import_file file on file.id = inventory.import_file_id and file.active = true
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

    private Map<String, List<ReplenishmentPlanner.ScheduledSupply>> incomingBySku(LocalDate snapshotDate) {
        Map<String, List<ReplenishmentPlanner.ScheduledSupply>> result = new LinkedHashMap<>();
        transitInventoryService.suppliesAvailableWithin(snapshotDate, HORIZON_DAYS).forEach(supply ->
                result.computeIfAbsent(supply.sku(), ignored -> new ArrayList<>())
                        .add(new ReplenishmentPlanner.ScheduledSupply(supply.availableDay(), supply.units())));
        return result;
    }

    private Map<String, List<ReplenishmentPlanner.ScheduledSupply>> factoryWipBySku(
            LocalDate snapshotDate,
            List<String> warnings
    ) {
        Map<String, List<ReplenishmentPlanner.ScheduledSupply>> result = new LinkedHashMap<>();
        for (ProductionOrderRecord order : productionOrderRepository.findCurrentVersions()) {
            if (order.getSku() == null || order.getSku().isBlank()) {
                continue;
            }
            if (order.getOrderDate() == null) {
                warnings.add(order.getSku() + ": production order date is missing; WIP was not deducted");
                continue;
            }
            LocalDate returnDate = ImportValues.date(order.getFactoryReturnDate());
            LocalDate availableDate = returnDate == null ? order.getOrderDate().plusDays(30) : returnDate;
            long days = ChronoUnit.DAYS.between(snapshotDate, availableDate);
            if (days < 1 || days > HORIZON_DAYS) {
                continue;
            }
            BigDecimal preferred = order.getCuttingQty();
            BigDecimal quantity = preferred != null && preferred.signum() > 0 ? preferred : order.getUnfinishedQty();
            if (quantity == null || quantity.signum() <= 0) {
                continue;
            }
            result.computeIfAbsent(order.getSku(), ignored -> new ArrayList<>())
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
