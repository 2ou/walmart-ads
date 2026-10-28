package com.ai.walmartads.product.application;

import com.ai.walmartads.common.UsBusinessDate;
import com.ai.walmartads.workspace.WorkspaceContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 款号驾驶舱的轻量聚合层。
 *
 * <p>只读取当前款号需要的订单、广告与库存切片，不加载全店销量页/库存页的大结果集。
 * 订单和广告统一使用最近可比的 30 天窗口；库存继续沿用“拒绝异常截断快照”的完整性口径。
 */
@Service
public class ProductDashboardService {

    static final int WINDOW_DAYS = 30;
    static final int MIN_COMPLETE_ROWS = 100;
    static final double MIN_COMPLETE_RATIO = 0.60;

    private final NamedParameterJdbcTemplate jdbc;

    public ProductDashboardService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 单款驾驶舱数据指纹，仅覆盖会改变驾驶舱的事实表。 */
    @Transactional(readOnly = true)
    public String version(String parentSku) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workspaceId", WorkspaceContext.requireId())
                .addValue("parentSku", parentSku);
        String value = jdbc.queryForObject("""
                select concat_ws('|',
                    (select coalesce(max(id),0) from wm_ads_order_record_valid
                      where workspace_id=:workspaceId and parent_sku=:parentSku),
                    (select coalesce(max(id),0) from wm_ads_lx_ad_report_raw
                      where workspace_id=:workspaceId and parent_sku=:parentSku and report_level='AD_ITEM'),
                    (select coalesce(max(snapshot_at),timestamp '1970-01-01 00:00:00')
                       from wm_ads_inventory_unified where workspace_id=:workspaceId),
                    (select coalesce(max(updated_at),timestamp '1970-01-01 00:00:00')
                       from wm_ads_lx_domestic_inventory where workspace_id=:workspaceId),
                    (select coalesce(max(synced_at),timestamp '1970-01-01 00:00:00')
                       from wm_ads_shipment_record where workspace_id=:workspaceId and current_version=true)
                )
                """, params, String.class);
        return value == null ? "" : value;
    }

    @Transactional(readOnly = true)
    public ProductDashboard load(String parentSku, List<String> skus) {
        long workspaceId = WorkspaceContext.requireId();
        LocalDate visibleDate = UsBusinessDate.visibleSalesDate();
        MapSqlParameterSource scope = new MapSqlParameterSource()
                .addValue("workspaceId", workspaceId)
                .addValue("parentSku", parentSku)
                .addValue("visibleDate", visibleDate);

        LocalDate salesLatest = date(queryScalar("""
                select max(order_date)
                  from wm_ads_order_record_valid
                 where workspace_id=:workspaceId and order_date<=:visibleDate
                """, scope));
        LocalDate adsLatest = date(queryScalar("""
                select max(report_date)
                  from wm_ads_lx_ad_report_raw
                 where workspace_id=:workspaceId and report_level='AD_ITEM' and report_date<=:visibleDate
                """, scope));
        LocalDate end = comparableEnd(salesLatest, adsLatest, visibleDate);
        LocalDate start = end.minusDays(WINDOW_DAYS - 1L);
        scope.addValue("start", start).addValue("end", end);

        Map<LocalDate, MutableTrend> trend = new LinkedHashMap<>();
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            trend.put(day, new MutableTrend(day));
        }

        jdbc.queryForList("""
                select order_date as business_date,
                       sum(quantity) as units,
                       count(distinct order_number) as orders,
                       coalesce(nullif(sum(quantity * nullif(original_price, 0)), 0),
                                sum(quantity * nullif(item_cost, 0)), 0) as revenue
                  from wm_ads_order_record_valid
                 where workspace_id=:workspaceId
                   and parent_sku=:parentSku
                   and order_date between :start and :end
                 group by order_date
                 order by order_date
                """, scope).forEach(row -> {
            MutableTrend value = trend.get(date(row.get("business_date")));
            if (value != null) {
                value.units = number(row.get("units"));
                value.orders = Math.round(number(row.get("orders")));
                value.revenue = number(row.get("revenue"));
            }
        });

        jdbc.queryForList("""
                select report_date as business_date,
                       sum(impressions) as impressions,
                       sum(clicks) as clicks,
                       sum(ad_spend) as ad_spend,
                       sum(attributed_sales) as ad_sales,
                       sum(attributed_orders) as ad_orders
                  from wm_ads_lx_ad_report_raw
                 where workspace_id=:workspaceId
                   and report_level='AD_ITEM'
                   and parent_sku=:parentSku
                   and report_date between :start and :end
                 group by report_date
                 order by report_date
                """, scope).forEach(row -> {
            MutableTrend value = trend.get(date(row.get("business_date")));
            if (value != null) {
                value.impressions = number(row.get("impressions"));
                value.clicks = number(row.get("clicks"));
                value.adSpend = number(row.get("ad_spend"));
                value.adSales = number(row.get("ad_sales"));
                value.adOrders = number(row.get("ad_orders"));
            }
        });

        List<TrendPoint> trendPoints = trend.values().stream().map(MutableTrend::view).toList();
        LocalDateTime inventoryAt = latestCompleteInventorySnapshot(workspaceId);
        Map<String, StockValues> stockBySku = stockBySku(workspaceId, inventoryAt, skus);
        Map<String, SalesValues> salesBySku = salesBySku(scope, skus);

        List<VariantPerformance> variants = new ArrayList<>();
        for (String sku : skus) {
            StockValues stock = stockBySku.getOrDefault(sku, StockValues.EMPTY);
            SalesValues sales = salesBySku.getOrDefault(sku, SalesValues.EMPTY);
            variants.add(new VariantPerformance(
                    sku,
                    round(sales.units),
                    round(sales.revenue),
                    round(stock.wfs),
                    round(stock.domestic),
                    round(stock.transit)
            ));
        }

        double units = trendPoints.stream().mapToDouble(TrendPoint::units).sum();
        long orders = trendPoints.stream().mapToLong(TrendPoint::orders).sum();
        double revenue = trendPoints.stream().mapToDouble(TrendPoint::revenue).sum();
        double impressions = trendPoints.stream().mapToDouble(TrendPoint::impressions).sum();
        double clicks = trendPoints.stream().mapToDouble(TrendPoint::clicks).sum();
        double adSpend = trendPoints.stream().mapToDouble(TrendPoint::adSpend).sum();
        double adSales = trendPoints.stream().mapToDouble(TrendPoint::adSales).sum();
        double adOrders = trendPoints.stream().mapToDouble(TrendPoint::adOrders).sum();
        double wfs = stockBySku.values().stream().mapToDouble(value -> value.wfs).sum();
        double domestic = stockBySku.values().stream().mapToDouble(value -> value.domestic).sum();
        double transit = stockBySku.values().stream().mapToDouble(value -> value.transit).sum();
        double dailyUnits = units / WINDOW_DAYS;

        Summary summary = new Summary(
                round(units),
                orders,
                round(revenue),
                ratio(revenue, units),
                round(wfs),
                round(domestic),
                round(transit),
                dailyUnits > 0 ? round(wfs / dailyUnits) : null,
                round(impressions),
                round(clicks),
                round(adSpend),
                round(adSales),
                round(adOrders),
                ratio(adSales, adSpend),
                rate(adSpend, adSales),
                rate(clicks, impressions),
                rate(adOrders, clicks)
        );

        LocalDateTime domesticAt = dateTime(queryScalar("""
                select max(synced_at) from wm_ads_lx_domestic_inventory where workspace_id=:workspaceId
                """, scope));
        LocalDateTime transitAt = dateTime(queryScalar("""
                select max(synced_at) from wm_ads_shipment_record
                 where workspace_id=:workspaceId and current_version=true
                """, scope));
        return new ProductDashboard(
                start,
                end,
                salesLatest,
                adsLatest,
                inventoryAt,
                domesticAt,
                transitAt,
                summary,
                trendPoints,
                variants
        );
    }

    private Map<String, SalesValues> salesBySku(MapSqlParameterSource scope, List<String> skus) {
        if (skus.isEmpty()) return Map.of();
        MapSqlParameterSource params = copy(scope).addValue("skus", skus);
        Map<String, SalesValues> result = new LinkedHashMap<>();
        jdbc.queryForList("""
                select sku,
                       sum(quantity) as units,
                       coalesce(nullif(sum(quantity * nullif(original_price, 0)), 0),
                                sum(quantity * nullif(item_cost, 0)), 0) as revenue
                  from wm_ads_order_record_valid
                 where workspace_id=:workspaceId
                   and sku in (:skus)
                   and order_date between :start and :end
                 group by sku
                """, params).forEach(row -> result.put(
                String.valueOf(row.get("sku")),
                new SalesValues(number(row.get("units")), number(row.get("revenue")))
        ));
        return result;
    }

    private Map<String, StockValues> stockBySku(long workspaceId, LocalDateTime inventoryAt, List<String> skus) {
        if (skus.isEmpty()) return Map.of();
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("workspaceId", workspaceId)
                .addValue("skus", skus)
                .addValue("inventoryAt", inventoryAt);
        Map<String, MutableStock> result = new LinkedHashMap<>();
        skus.forEach(sku -> result.put(sku, new MutableStock()));
        if (inventoryAt != null) {
            jdbc.queryForList("""
                    select sku, sum(available_units) as units
                      from wm_ads_inventory_unified
                     where workspace_id=:workspaceId and snapshot_at=:inventoryAt and sku in (:skus)
                     group by sku
                    """, params).forEach(row -> result.get(String.valueOf(row.get("sku"))).wfs = number(row.get("units")));
        }
        jdbc.queryForList("""
                select sku, sum(available_units) as units
                  from wm_ads_lx_domestic_inventory
                 where workspace_id=:workspaceId and sku in (:skus)
                 group by sku
                """, params).forEach(row -> result.get(String.valueOf(row.get("sku"))).domestic = number(row.get("units")));
        jdbc.queryForList("""
                select sku, sum(greatest(receipt_difference, 0)) as units
                  from wm_ads_shipment_record
                 where workspace_id=:workspaceId
                   and current_version=true
                   and local_status in ('入库中','已发货','已申报')
                   and sku in (:skus)
                 group by sku
                """, params).forEach(row -> result.get(String.valueOf(row.get("sku"))).transit = number(row.get("units")));

        Map<String, StockValues> views = new LinkedHashMap<>();
        result.forEach((sku, value) -> views.put(sku, value.view()));
        return views;
    }

    private LocalDateTime latestCompleteInventorySnapshot(long workspaceId) {
        List<SnapshotCandidate> candidates = jdbc.queryForList("""
                select snapshot_at, count(*) as row_count
                  from wm_ads_inventory_unified
                 where workspace_id=:workspaceId
                 group by snapshot_at
                 order by snapshot_at desc
                 limit 2
                """, new MapSqlParameterSource("workspaceId", workspaceId)).stream()
                .map(row -> new SnapshotCandidate(dateTime(row.get("snapshot_at")), (int) number(row.get("row_count"))))
                .toList();
        return selectCompleteSnapshot(candidates);
    }

    static LocalDateTime selectCompleteSnapshot(List<SnapshotCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return null;
        SnapshotCandidate latest = candidates.getFirst();
        if (candidates.size() == 1) return latest.snapshotAt();
        SnapshotCandidate previous = candidates.get(1);
        boolean truncated = previous.rows() >= MIN_COMPLETE_ROWS
                && latest.rows() < Math.ceil(previous.rows() * MIN_COMPLETE_RATIO);
        return truncated ? previous.snapshotAt() : latest.snapshotAt();
    }

    static LocalDate comparableEnd(LocalDate salesLatest, LocalDate adsLatest, LocalDate fallback) {
        if (salesLatest != null && adsLatest != null) return salesLatest.isBefore(adsLatest) ? salesLatest : adsLatest;
        if (salesLatest != null) return salesLatest;
        if (adsLatest != null) return adsLatest;
        return fallback;
    }

    private Object queryScalar(String sql, MapSqlParameterSource params) {
        List<Object> rows = jdbc.query(sql, params, (rs, rowNum) -> rs.getObject(1));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static MapSqlParameterSource copy(MapSqlParameterSource source) {
        MapSqlParameterSource target = new MapSqlParameterSource();
        source.getValues().forEach(target::addValue);
        return target;
    }

    private static double ratio(double numerator, double denominator) {
        return denominator == 0 ? 0 : round(numerator / denominator);
    }

    static double rate(double numerator, double denominator) {
        if (denominator == 0) return 0;
        double value = numerator / denominator;
        if (!Double.isFinite(value)) return 0;
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static double round(double value) {
        if (!Double.isFinite(value)) return 0;
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static double number(Object value) {
        if (value == null) return 0;
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static LocalDate date(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate date) return date;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        return LocalDate.parse(String.valueOf(value));
    }

    private static LocalDateTime dateTime(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        return LocalDateTime.parse(String.valueOf(value).replace(' ', 'T'));
    }

    static record SnapshotCandidate(LocalDateTime snapshotAt, int rows) {
    }

    private static final class MutableTrend {
        private final LocalDate date;
        private double units;
        private long orders;
        private double revenue;
        private double impressions;
        private double clicks;
        private double adSpend;
        private double adSales;
        private double adOrders;

        private MutableTrend(LocalDate date) {
            this.date = date;
        }

        private TrendPoint view() {
            return new TrendPoint(date, round(units), orders, round(revenue), round(impressions), round(clicks),
                    round(adSpend), round(adSales), round(adOrders));
        }
    }

    private static final class MutableStock {
        private double wfs;
        private double domestic;
        private double transit;

        private StockValues view() {
            return new StockValues(wfs, domestic, transit);
        }
    }

    private record StockValues(double wfs, double domestic, double transit) {
        private static final StockValues EMPTY = new StockValues(0, 0, 0);
    }

    private record SalesValues(double units, double revenue) {
        private static final SalesValues EMPTY = new SalesValues(0, 0);
    }

    public record ProductDashboard(
            LocalDate startDate,
            LocalDate endDate,
            LocalDate salesDataDate,
            LocalDate adsDataDate,
            LocalDateTime inventoryDataAt,
            LocalDateTime domesticDataAt,
            LocalDateTime transitDataAt,
            Summary summary,
            List<TrendPoint> trend,
            List<VariantPerformance> variants
    ) {
    }

    public record Summary(
            double salesUnits,
            long orders,
            double revenue,
            double averageSellingPrice,
            double wfsAvailable,
            double domesticAvailable,
            double transitUnits,
            Double daysOfSupply,
            double impressions,
            double clicks,
            double adSpend,
            double adSales,
            double adOrders,
            double roas,
            double acos,
            double ctr,
            double conversionRate
    ) {
    }

    public record TrendPoint(
            LocalDate date,
            double units,
            long orders,
            double revenue,
            double impressions,
            double clicks,
            double adSpend,
            double adSales,
            double adOrders
    ) {
    }

    public record VariantPerformance(
            String sku,
            double salesUnits,
            double revenue,
            double wfsAvailable,
            double domesticAvailable,
            double transitUnits
    ) {
    }
}
