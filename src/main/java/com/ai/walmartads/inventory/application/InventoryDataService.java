package com.ai.walmartads.inventory.application;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InventoryDataService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final NamedParameterJdbcTemplate jdbc;

    public InventoryDataService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public InventoryPayload load(int days, int targetDays) {
        int safeDays = Math.max(1, Math.min(days, 365));
        int safeTargetDays = Math.max(7, Math.min(targetDays, 180));
        LocalDateTime snapshotAt = latestSnapshotAt();
        if (snapshotAt == null) {
            return emptyPayload(safeDays, safeTargetDays);
        }

        SalesWindow salesWindow = salesWindow(snapshotAt.toLocalDate(), safeDays);
        Map<String, BigDecimal> salesBySku = salesBySku(salesWindow.startDate(), salesWindow.latestDate());
        Map<String, ProductionPlan> plans = latestProductionPlans();
        List<Map<String, Object>> inventory = jdbc.queryForList("""
                select inventory.sku,
                       inventory.parent_sku,
                       inventory.item_id,
                       inventory.item_name,
                       inventory.status,
                       inventory.daily_units_sold,
                       inventory.available_units,
                       inventory.reserved_units,
                       inventory.inbound_units,
                       inventory.forecasted_days_supply,
                       inventory.out_of_stock_date,
                       inventory.suggested_units,
                       inventory.surplus_units,
                       inventory.sell_through_rate,
                       inventory.publishing_status,
                       inventory.item_lifecycle,
                       product.main_image_url
                  from wm_ads_inventory_snapshot inventory
                  join wm_ads_import_file inventory_file
                    on inventory_file.id = inventory.import_file_id
                   and inventory_file.active = true
                  left join (
                        select sku, max(main_image_url) as main_image_url
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
            ProductionPlan plan = plans.getOrDefault(sku, ProductionPlan.empty(sku));
            double recentUnits = decimal(salesBySku.get(sku)).doubleValue();
            double reportDailyUnits = number(source, "daily_units_sold");
            double availableUnits = number(source, "available_units");
            double transitUnits = number(source, "inbound_units");
            double productionUnits = plan.productionUnits().doubleValue();
            double surplusUnits = number(source, "surplus_units");
            InventoryForecastCalculator.Forecast forecast = InventoryForecastCalculator.calculate(
                    recentUnits,
                    salesWindow.sampleDays(),
                    reportDailyUnits,
                    availableUnits,
                    transitUnits,
                    productionUnits,
                    surplusUnits,
                    safeTargetDays
            );
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
                    round(productionUnits),
                    round(number(source, "suggested_units")),
                    forecast.forecastSuggestedUnits(),
                    round(number(source, "forecasted_days_supply")),
                    forecast.projectedDaysSupply(),
                    round(number(source, "sell_through_rate")),
                    localDate(source.get("out_of_stock_date")),
                    text(source, "publishing_status"),
                    text(source, "item_lifecycle"),
                    forecast.risk(),
                    forecast.confidence(),
                    plan.updatedAt(),
                    plan.note(),
                    "ceil(max(0, 预测日均销量 × " + safeTargetDays + " - 可售库存 - 做货库存 - 路途库存))"
            ));
        }
        rows.sort(Comparator
                .comparingInt((InventoryRow row) -> riskPriority(row.risk()))
                .thenComparing(InventoryRow::forecastSuggestedUnits, Comparator.reverseOrder())
                .thenComparing(InventoryRow::sku));

        Summary summary = summary(rows);
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
        return new InventoryPayload(meta, summary, riskCounts, loadTrend(), rows);
    }

    @Transactional
    public ProductionEntry saveProductionStock(String sku, BigDecimal productionUnits, String note) {
        String safeSku = sku == null ? "" : sku.trim();
        if (safeSku.isEmpty()) {
            throw new IllegalArgumentException("SKU 不能为空。");
        }
        BigDecimal safeUnits = productionUnits == null ? ZERO : productionUnits;
        if (safeUnits.signum() < 0 || safeUnits.compareTo(new BigDecimal("10000000")) > 0) {
            throw new IllegalArgumentException("做货库存必须在 0 到 10000000 之间。");
        }
        String safeNote = note == null ? null : note.trim();
        if (safeNote != null && safeNote.length() > 500) {
            throw new IllegalArgumentException("备注不能超过 500 个字符。");
        }
        if (!inventorySkuExists(safeSku)) {
            throw new IllegalArgumentException("当前库存快照中找不到 SKU：" + safeSku);
        }
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("""
                insert into wm_ads_inventory_supply_plan (sku, production_units, note, created_at)
                values (:sku, :productionUnits, :note, :createdAt)
                """, new MapSqlParameterSource()
                .addValue("sku", safeSku)
                .addValue("productionUnits", safeUnits.setScale(4, RoundingMode.HALF_UP))
                .addValue("note", safeNote)
                .addValue("createdAt", now));
        return new ProductionEntry(safeSku, round(safeUnits.doubleValue()), safeNote, now);
    }

    @Transactional(readOnly = true)
    public List<ProductionEntry> productionHistory(String sku, int limit) {
        String safeSku = sku == null ? "" : sku.trim();
        if (safeSku.isEmpty()) {
            throw new IllegalArgumentException("SKU 不能为空。");
        }
        int safeLimit = Math.max(1, Math.min(limit, 100));
        return jdbc.queryForList("""
                select sku, production_units, note, created_at
                  from wm_ads_inventory_supply_plan
                 where sku = :sku
                 order by id desc
                 limit :limit
                """, new MapSqlParameterSource()
                .addValue("sku", safeSku)
                .addValue("limit", safeLimit)).stream().map(row -> new ProductionEntry(
                        text(row, "sku"),
                        round(number(row, "production_units")),
                        text(row, "note"),
                        localDateTime(row.get("created_at"))
                )).toList();
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

    private SalesWindow salesWindow(LocalDate fallbackLatestDate, int days) {
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
        LocalDate startDate = latestDate.minusDays(days - 1L);
        Integer sampleDays = jdbc.queryForObject("""
                select count(distinct orders.report_date)
                  from wm_ads_order_record orders
                  join wm_ads_import_file file on file.id = orders.import_file_id and file.active = true
                 where orders.report_date between :startDate and :latestDate
                """, new MapSqlParameterSource()
                .addValue("startDate", startDate)
                .addValue("latestDate", latestDate), Integer.class);
        return new SalesWindow(firstDate, latestDate, startDate, sampleDays == null ? 0 : sampleDays);
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

    private Map<String, ProductionPlan> latestProductionPlans() {
        Map<String, ProductionPlan> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                select plan.sku, plan.production_units, plan.note, plan.created_at
                  from wm_ads_inventory_supply_plan plan
                  join (
                        select sku, max(id) as latest_id
                          from wm_ads_inventory_supply_plan
                         group by sku
                  ) latest on latest.latest_id = plan.id
                """, new MapSqlParameterSource()).forEach(row -> {
                    String sku = text(row, "sku");
                    result.put(sku, new ProductionPlan(
                            sku,
                            decimal(row.get("production_units")),
                            text(row, "note"),
                            localDateTime(row.get("created_at"))
                    ));
                });
        return result;
    }

    private List<TrendRow> loadTrend() {
        return jdbc.queryForList("""
                select inventory.snapshot_at,
                       sum(inventory.available_units) as available_units,
                       sum(inventory.reserved_units) as reserved_units,
                       sum(inventory.inbound_units) as transit_units,
                       sum(inventory.suggested_units) as system_suggested_units
                  from wm_ads_inventory_snapshot inventory
                  join wm_ads_import_file file on file.id = inventory.import_file_id and file.active = true
                 group by inventory.snapshot_at
                 order by inventory.snapshot_at
                """, new MapSqlParameterSource()).stream().map(row -> new TrendRow(
                        localDateTime(row.get("snapshot_at")),
                        round(number(row, "available_units")),
                        round(number(row, "reserved_units")),
                        round(number(row, "transit_units")),
                        round(number(row, "system_suggested_units"))
                )).toList();
    }

    private boolean inventorySkuExists(String sku) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from wm_ads_inventory_snapshot inventory
                  join wm_ads_import_file file on file.id = inventory.import_file_id and file.active = true
                 where inventory.snapshot_at = (
                       select max(latest.snapshot_at)
                         from wm_ads_inventory_snapshot latest
                         join wm_ads_import_file latest_file
                           on latest_file.id = latest.import_file_id and latest_file.active = true
                 )
                   and inventory.sku = :sku
                """, new MapSqlParameterSource("sku", sku), Integer.class);
        return count != null && count > 0;
    }

    private static Summary summary(List<InventoryRow> rows) {
        return new Summary(
                round(rows.stream().mapToDouble(InventoryRow::availableUnits).sum()),
                round(rows.stream().mapToDouble(InventoryRow::reservedUnits).sum()),
                round(rows.stream().mapToDouble(InventoryRow::transitUnits).sum()),
                round(rows.stream().mapToDouble(InventoryRow::productionUnits).sum()),
                round(rows.stream().mapToDouble(InventoryRow::systemSuggestedUnits).sum()),
                round(rows.stream().mapToDouble(InventoryRow::forecastSuggestedUnits).sum()),
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
                new Summary(0, 0, 0, 0, 0, 0, 0, 0),
                Map.of(),
                List.of(),
                List.of()
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
            List<InventoryRow> rows
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
            String formula
    ) {
    }

    public record TrendRow(
            LocalDateTime snapshotAt,
            double availableUnits,
            double reservedUnits,
            double transitUnits,
            double systemSuggestedUnits
    ) {
    }

    public record ProductionEntry(
            String sku,
            double productionUnits,
            String note,
            LocalDateTime createdAt
    ) {
    }

    private record ProductionPlan(
            String sku,
            BigDecimal productionUnits,
            String note,
            LocalDateTime updatedAt
    ) {
        private static ProductionPlan empty(String sku) {
            return new ProductionPlan(sku, ZERO, null, null);
        }
    }

    private record SalesWindow(
            LocalDate firstDate,
            LocalDate latestDate,
            LocalDate startDate,
            int sampleDays
    ) {
    }
}
