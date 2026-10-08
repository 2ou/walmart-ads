package com.ai.walmartads.analytics.application;

import com.ai.walmartads.common.UsBusinessDate;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 近期数据：按天聚合订单（件数 / 销售额 / 客单价）与广告（自动 + 手动，全部 AD 类型，
 * 使用领星 AD_ITEM 日事实聚合曝光、点击、广告费、广告订单和广告归因销售，
 * 输出窗口期 KPI 汇总与逐日明细。口径与「广告表现」块、原 Excel 总广告费一致。
 *
 * 数据口径对齐参考报表「PNK 每日销售数据统计」：日期 / 总订单 / 总销售额 / 客单价 /
 * 曝光量 / 点击量 / 广告费 / 广告订单 / CTR / CPC / 转化率 / 广告订单占比 / 自然订单。
 */
@Service
public class RecentDataService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int SCALE = 4;

    private final NamedParameterJdbcTemplate jdbc;

    public RecentDataService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public RecentDataPayload load(int days) {
        int window = Math.max(1, Math.min(days, 365));
        LocalDate orderLatest = latestOrderDate();
        LocalDate adLatest = latestAdReportDate();
        LocalDate end = comparableLatestDate(orderLatest, adLatest, UsBusinessDate.visibleSalesDate());
        LocalDate start = end.minusDays(window - 1L);

        Map<String, Object> params = new java.util.HashMap<>();
        params.put("start", start);
        params.put("end", end);

        List<Map<String, Object>> orderRows = jdbc.queryForList("""
                select o.order_date as report_date,
                       sum(o.quantity) as orders,
                       coalesce(nullif(sum(o.quantity * nullif(o.original_price, 0)), 0),
                               sum(o.quantity * nullif(o.item_cost, 0)), 0) as revenue
                  from wm_ads_order_record_valid o
                  join wm_ads_import_file f on f.id = o.import_file_id and f.active = true
                 where o.order_date >= :start and o.order_date <= :end
                 group by o.order_date
                """, params);

        List<Map<String, Object>> adRows = jdbc.queryForList("""
                select a.report_date as report_date,
                       sum(a.impressions) as impressions,
                       sum(a.clicks) as clicks,
                       sum(a.ad_spend) as spend,
                       sum(a.attributed_orders) as ad_orders,
                       sum(a.attributed_sales) as ad_sales
                  from wm_ads_lx_ad_report a
                 where a.report_level = 'AD_ITEM'
                   and a.report_date >= :start and a.report_date <= :end
                 group by a.report_date
                """, params);

        TreeMap<LocalDate, MutableDay> map = new TreeMap<>();
        orderRows.forEach(row -> {
            MutableDay day = map.computeIfAbsent(date(row, "report_date"), MutableDay::new);
            day.orders = decimal(row, "orders");
            day.revenue = decimal(row, "revenue");
        });
        adRows.forEach(row -> {
            MutableDay day = map.computeIfAbsent(date(row, "report_date"), MutableDay::new);
            day.impressions = decimal(row, "impressions");
            day.clicks = decimal(row, "clicks");
            day.spend = decimal(row, "spend");
            day.adOrders = decimal(row, "ad_orders");
            day.adSales = decimal(row, "ad_sales");
        });

        List<RecentDailyRow> daily = map.values().stream().map(MutableDay::toRow).toList();
        RecentSummary summary = summarize(daily);
        return new RecentDataPayload(start, end, window, summary, daily);
    }

    private RecentSummary summarize(List<RecentDailyRow> rows) {
        BigDecimal orders = ZERO, revenue = ZERO, impressions = ZERO, clicks = ZERO,
                spend = ZERO, adOrders = ZERO, naturalOrders = ZERO, adSales = ZERO;
        for (RecentDailyRow r : rows) {
            orders = orders.add(BigDecimal.valueOf(r.orders()));
            revenue = revenue.add(BigDecimal.valueOf(r.revenue()));
            impressions = impressions.add(BigDecimal.valueOf(r.impressions()));
            clicks = clicks.add(BigDecimal.valueOf(r.clicks()));
            spend = spend.add(BigDecimal.valueOf(r.spend()));
            adOrders = adOrders.add(BigDecimal.valueOf(r.adOrders()));
            naturalOrders = naturalOrders.add(BigDecimal.valueOf(r.naturalOrders()));
            adSales = adSales.add(BigDecimal.valueOf(r.adSales()));
        }
        Derived d = derive(orders, revenue, impressions, clicks, spend, adOrders);
        return new RecentSummary(
                orders.doubleValue(), revenue.doubleValue(), d.avgOrderValue,
                impressions.doubleValue(), clicks.doubleValue(), spend.doubleValue(),
                adOrders.doubleValue(), naturalOrders.doubleValue(),
                d.ctr, d.cpc, d.conversionRate, d.adOrderRatio, adSales.doubleValue());
    }

    private record Derived(
            double avgOrderValue, double ctr, double cpc, double conversionRate, double adOrderRatio
    ) {
    }

    private static Derived derive(BigDecimal orders, BigDecimal revenue, BigDecimal impressions,
                                  BigDecimal clicks, BigDecimal spend, BigDecimal adOrders) {
        double avgOrderValue = orders.signum() > 0 ? revenue.divide(orders, SCALE, RoundingMode.HALF_UP).doubleValue() : 0;
        double ctr = impressions.signum() > 0 ? clicks.divide(impressions, SCALE, RoundingMode.HALF_UP).doubleValue() : 0;
        double cpc = clicks.signum() > 0 ? spend.divide(clicks, SCALE, RoundingMode.HALF_UP).doubleValue() : 0;
        double conversionRate = clicks.signum() > 0 ? adOrders.divide(clicks, SCALE, RoundingMode.HALF_UP).doubleValue() : 0;
        double adOrderRatio = orders.signum() > 0 ? adOrders.divide(orders, SCALE, RoundingMode.HALF_UP).doubleValue() : 0;
        return new Derived(avgOrderValue, ctr, cpc, conversionRate, adOrderRatio);
    }

    /**
     * 订单最新业务日：与经营驾驶舱 / 销量分析对齐，统一使用 order_date（T+1），
     * 而非 report_date。若数据两列分化，report_date 会偏离订单真实发生日。
     */
    private LocalDate latestOrderDate() {
        LocalDate latest = jdbc.getJdbcTemplate().queryForObject(
                "select max(order_date) from wm_ads_order_record_valid where order_date is not null", LocalDate.class);
        return latest == null ? UsBusinessDate.usToday() : latest;
    }

    /**
     * 广告最新业务日：领星广告报表为 T+0，与订单 T+1 经常差一天。用于与订单对齐到可比日。
     */
    private LocalDate latestAdReportDate() {
        LocalDate latest = jdbc.getJdbcTemplate().queryForObject(
                "select max(report_date) from wm_ads_lx_ad_report_raw where report_level = 'AD_ITEM'", LocalDate.class);
        return latest == null ? UsBusinessDate.usToday() : latest;
    }

    /**
     * 与经营驾驶舱保持完全一致的「可比最新日」：订单 / 广告 / 美西今日三者取最小，
     * 避免末日出现「只有广告、没有订单」的孤岛日（订单 T+1、广告 T+0）。
     */
    static LocalDate comparableLatestDate(LocalDate orderLatestDate, LocalDate adLatestDate, LocalDate usToday) {
        return java.util.stream.Stream.of(orderLatestDate, adLatestDate, usToday)
                .filter(java.util.Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(usToday);
    }

    private static LocalDate date(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private static BigDecimal decimal(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static final class MutableDay {
        private final LocalDate date;
        private BigDecimal orders = ZERO;
        private BigDecimal revenue = ZERO;
        private BigDecimal impressions = ZERO;
        private BigDecimal clicks = ZERO;
        private BigDecimal spend = ZERO;
        private BigDecimal adOrders = ZERO;
        private BigDecimal adSales = ZERO;

        private MutableDay(LocalDate date) {
            this.date = date;
        }

        private RecentDailyRow toRow() {
            BigDecimal naturalOrders = orders.subtract(adOrders).max(ZERO);
            Derived d = derive(orders, revenue, impressions, clicks, spend, adOrders);
            return new RecentDailyRow(
                    date,
                    orders.doubleValue(),
                    revenue.doubleValue(),
                    d.avgOrderValue,
                    impressions.doubleValue(),
                    clicks.doubleValue(),
                    spend.doubleValue(),
                    adOrders.doubleValue(),
                    naturalOrders.doubleValue(),
                    d.ctr,
                    d.cpc,
                    d.conversionRate,
                    d.adOrderRatio,
                    adSales.doubleValue());
        }
    }

    public record RecentDataPayload(
            LocalDate start,
            LocalDate end,
            int days,
            RecentSummary summary,
            List<RecentDailyRow> daily
    ) {
    }

    public record RecentSummary(
            double totalOrders,
            double totalRevenue,
            double avgOrderValue,
            double totalImpressions,
            double totalClicks,
            double totalSpend,
            double totalAdOrders,
            double totalNaturalOrders,
            double ctr,
            double cpc,
            double conversionRate,
            double adOrderRatio,
            double totalAdSales
    ) {
    }

    public record RecentDailyRow(
            LocalDate date,
            double orders,
            double revenue,
            double avgOrderValue,
            double impressions,
            double clicks,
            double spend,
            double adOrders,
            double naturalOrders,
            double ctr,
            double cpc,
            double conversionRate,
            double adOrderRatio,
            double adSales
    ) {
    }
}
