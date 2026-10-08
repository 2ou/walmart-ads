package com.ai.walmartads.inventory.application;

import com.ai.walmartads.importing.repository.DemandForecastRepository;
import com.ai.walmartads.product.application.RefundRateService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 需求预测视图：把「Excel 计划值」与「系统实际值」按 SKU 关联。
 * 实际值（颜色/品名/WFS可售/近30天销量/近3月日均/周转天数/退货率）全部来自系统现有数据源，不存储。
 */
@Service
public class DemandForecastService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int AVG_WINDOW_DAYS = 90;

    private final NamedParameterJdbcTemplate jdbc;
    private final DemandForecastRepository demandForecastRepository;
    private final RefundRateService refundRateService;

    public DemandForecastService(
            NamedParameterJdbcTemplate jdbc,
            DemandForecastRepository demandForecastRepository,
            RefundRateService refundRateService
    ) {
        this.jdbc = jdbc;
        this.demandForecastRepository = demandForecastRepository;
        this.refundRateService = refundRateService;
    }

    public DemandForecastPayload load() {
        List<DemandForecastRecordView> planRows = demandForecastRepository.findCurrentVersions().stream()
                .map(DemandForecastRecordView::from)
                .toList();
        if (planRows.isEmpty()) {
            return new DemandForecastPayload(null, List.of());
        }

        LocalDateTime updatedAt = localDateTime(jdbc.queryForList(
                "select max(created_at) as updated_at from wm_ads_demand_forecast where current_version = true",
                new MapSqlParameterSource()
        ).stream().findFirst().map(row -> row.get("updated_at")).orElse(null));

        List<String> skus = planRows.stream().map(DemandForecastRecordView::sku).toList();

        Map<String, ProductInfo> products = loadProducts(skus);
        Map<String, InventoryInfo> inventory = loadLatestInventory(skus);
        LocalDate latestOrderDate = latestOrderDate();
        Map<String, BigDecimal> sales30 = latestSales(skus, latestOrderDate.minusDays(29), latestOrderDate);
        Map<String, BigDecimal> sales90 = latestSales(skus, latestOrderDate.minusDays(AVG_WINDOW_DAYS - 1L), latestOrderDate);
        Map<String, BigDecimal> refundRates = refundRateService.ratesByParent();

        List<DemandForecastRow> rows = planRows.stream().map(plan -> {
            ProductInfo product = products.get(plan.sku());
            InventoryInfo inv = inventory.get(plan.sku());
            String parentSku = product != null && product.parentSku() != null
                    ? product.parentSku()
                    : ImportValuesParent.parentSku(plan.sku());
            BigDecimal returnRate = parentSku == null ? null : refundRates.get(parentSku);
            double avgDaily90 = decimal(sales90.get(plan.sku())).divide(
                    BigDecimal.valueOf(AVG_WINDOW_DAYS), 4, BigDecimal.ROUND_HALF_UP
            ).doubleValue();
            return new DemandForecastRow(
                    plan.sku(),
                    product == null ? null : product.color(),
                    parentSku,
                    inv == null ? null : round(inv.availableUnits()),
                    round(decimal(sales30.get(plan.sku()))),
                    avgDaily90,
                    inv == null || inv.daysSupply() == null ? null : round(inv.daysSupply()),
                    returnRate == null ? null : returnRate.doubleValue(),
                    decimalToDouble(plan.forecastPmc()),
                    decimalToDouble(plan.forecastOps()),
                    decimalToDouble(plan.pctGain()),
                    decimalToDouble(plan.reuseRate()),
                    decimalToDouble(plan.demandPlan()),
                    decimalToDouble(plan.actualOrder()),
                    decimalToDouble(plan.shipQty()),
                    decimalToDouble(plan.remaining())
            );
        }).toList();

        return new DemandForecastPayload(updatedAt, rows);
    }

    private Map<String, ProductInfo> loadProducts(List<String> skus) {
        Map<String, ProductInfo> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                select sku, parent_sku, color, product_name
                  from wm_ads_product_record
                 where current_version = true and sku in (:skus)
                """, new MapSqlParameterSource("skus", skus)).forEach(row -> result.put(
                text(row, "sku"),
                new ProductInfo(text(row, "parent_sku"), text(row, "color"), text(row, "product_name"))
        ));
        return result;
    }

    private Map<String, InventoryInfo> loadLatestInventory(List<String> skus) {
        LocalDateTime snapshotAt = latestSnapshotAt();
        Map<String, InventoryInfo> result = new LinkedHashMap<>();
        if (snapshotAt == null) {
            return result;
        }
        jdbc.queryForList("""
                select s.sku, s.available_units, s.forecasted_days_supply
                  from wm_ads_inventory_unified s
                 where s.snapshot_at = :snapshotAt and s.sku in (:skus)
                """, new MapSqlParameterSource("snapshotAt", snapshotAt).addValue("skus", skus))
                .forEach(row -> result.put(text(row, "sku"), new InventoryInfo(
                        decimal(row.get("available_units")),
                        decimal(row.get("forecasted_days_supply"))
                )));
        return result;
    }

    private LocalDate latestOrderDate() {
        LocalDate date = localDate(jdbc.queryForList("""
                select max(o.report_date) as latest
                  from wm_ads_order_record_valid o
                  join wm_ads_import_file f on f.id = o.import_file_id and f.active = true
                """, new MapSqlParameterSource()).stream().findFirst().map(row -> row.get("latest")).orElse(null));
        return date == null ? LocalDate.now() : date;
    }

    private LocalDateTime latestSnapshotAt() {
        return localDateTime(jdbc.queryForList("""
                select max(s.snapshot_at) as snapshot
                  from wm_ads_inventory_unified s
                """, new MapSqlParameterSource()).stream().findFirst().map(row -> row.get("snapshot")).orElse(null));
    }

    private Map<String, BigDecimal> latestSales(List<String> skus, LocalDate start, LocalDate end) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                select o.sku, sum(o.quantity) as units
                  from wm_ads_order_record_valid o
                  join wm_ads_import_file f on f.id = o.import_file_id and f.active = true
                 where o.report_date between :start and :end and o.sku in (:skus)
                 group by o.sku
                """, new MapSqlParameterSource("start", start).addValue("end", end).addValue("skus", skus))
                .forEach(row -> result.put(text(row, "sku"), decimal(row.get("units"))));
        return result;
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

    private static Double decimalToDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static double round(BigDecimal value) {
        return value == null ? 0d : value.setScale(4, BigDecimal.ROUND_HALF_UP).doubleValue();
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
        if (value instanceof java.sql.Date sqlDate) {
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
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return LocalDateTime.parse(String.valueOf(value).replace(" ", "T"));
    }

    public record DemandForecastPayload(LocalDateTime updatedAt, List<DemandForecastRow> rows) {
    }

    public record DemandForecastRow(
            String sku,
            String color,
            String styleNo,
            Double wfsAvailable,
            double salesLast30,
            double avgDaily90,
            Double turnoverDays,
            Double returnRate,
            Double forecastPmc,
            Double forecastOps,
            Double pctGain,
            Double reuseRate,
            Double demandPlan,
            Double actualOrder,
            Double shipQty,
            Double remaining
    ) {
    }

    private record DemandForecastRecordView(
            String sku,
            BigDecimal forecastPmc,
            BigDecimal forecastOps,
            BigDecimal pctGain,
            BigDecimal reuseRate,
            BigDecimal demandPlan,
            BigDecimal actualOrder,
            BigDecimal shipQty,
            BigDecimal remaining
    ) {
        static DemandForecastRecordView from(com.ai.walmartads.importing.domain.DemandForecastRecord r) {
            return new DemandForecastRecordView(
                    r.getSku(),
                    r.getForecastPmc(),
                    r.getForecastOps(),
                    r.getPctGain(),
                    r.getReuseRate(),
                    r.getDemandPlan(),
                    r.getActualOrder(),
                    r.getShipQty(),
                    r.getRemaining()
            );
        }
    }

    private record ProductInfo(String parentSku, String color, String productName) {
    }

    private record InventoryInfo(BigDecimal availableUnits, BigDecimal daysSupply) {
    }

    private static final class ImportValuesParent {
        static String parentSku(String sku) {
            if (sku == null || sku.isBlank()) {
                return null;
            }
            int separator = sku.indexOf('-');
            return separator > 0 ? sku.substring(0, separator) : sku;
        }
    }
}
