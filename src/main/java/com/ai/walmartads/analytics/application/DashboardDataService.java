package com.ai.walmartads.analytics.application;

import com.ai.walmartads.common.UsBusinessDate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

@Service
public class DashboardDataService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final NamedParameterJdbcTemplate jdbc;

    public DashboardDataService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public DashboardPayload load() {
        List<Map<String, Object>> orderDaily = jdbc.queryForList("""
                select orders.order_date as report_date,
                       sum(orders.quantity) as orders,
                       sum(orders.quantity * orders.item_cost) as revenue
                  from wm_ads_order_record_valid orders
                  join wm_ads_import_file file on file.id = orders.import_file_id and file.active = true
                 where orders.order_date is not null
                 group by orders.order_date
                """, new MapSqlParameterSource());
        List<Map<String, Object>> adDaily = jdbc.queryForList("""
                select ads.report_date as report_date,
                       sum(ads.impressions) as impressions,
                       sum(ads.clicks) as clicks,
                       sum(ads.ad_spend) as spend,
                       sum(ads.attributed_orders) as ad_orders,
                       sum(ads.attributed_sales) as ad_sales
                  from wm_ads_lx_ad_report ads
                 where ads.report_level = 'AD_ITEM'
                 group by ads.report_date
                """, new MapSqlParameterSource());
        LocalDate adLatestDate = adDaily.stream()
                .map(row -> date(row, "report_date"))
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElse(null);

        TreeMap<LocalDate, MutableDaily> daily = new TreeMap<>();
        orderDaily.forEach(row -> {
            MutableDaily item = daily.computeIfAbsent(date(row, "report_date"), MutableDaily::new);
            item.orders = decimal(row, "orders");
            item.revenue = decimal(row, "revenue");
        });
        adDaily.forEach(row -> {
            MutableDaily item = daily.computeIfAbsent(date(row, "report_date"), MutableDaily::new);
            item.impressions = decimal(row, "impressions");
            item.clicks = decimal(row, "clicks");
            item.spend = decimal(row, "spend");
            item.adOrders = decimal(row, "ad_orders");
            item.adSales = decimal(row, "ad_sales");
        });

        List<DailyRow> dailyRows = daily.values().stream().map(MutableDaily::toRow).toList();
        List<SkuDailyRow> skuRows = loadSkuDaily();
        List<CampaignDailyRow> campaignRows = loadCampaignDaily();

        LocalDate latestDate = daily.isEmpty() ? UsBusinessDate.usToday() : daily.lastKey();
        LocalDate firstDate = daily.isEmpty() ? latestDate : daily.firstKey();
        long importedFiles = count("select count(*) from wm_ads_import_file where status = 'IMPORTED'");
        long productRows = count("select count(*) from wm_ads_product_record where current_version = true");
        long inventoryRows = count("""
                select count(*) from wm_ads_inventory_unified inventory
                where inventory.snapshot_at = (
                      select max(latest.snapshot_at)
                        from wm_ads_inventory_unified latest
                )
                """);

        LocalDate orderLatestDate = latestOrderDate();
        Meta meta = new Meta(
                latestDate,
                firstDate,
                importedFiles,
                new SourceRows(dailyRows.size(), skuRows.size(), productRows, inventoryRows),
                "订单客户姓名、电话和完整地址未写入数据库。",
                orderLatestDate,
                adLatestDate,
                comparableLatestDate(orderLatestDate, adLatestDate, UsBusinessDate.visibleSalesDate())
        );
        Defaults defaults = new Defaults(7, 5.0, 1.5, 20, 10.0, 10);
        return new DashboardPayload(meta, defaults, dailyRows, skuRows, campaignRows);
    }

    /**
     * 订单口径最新业务日（美西日历日）。
     *
     * <p>广告报表（{@code wm_ads_lx_ad_report_raw}）是 T+0，订单销量统计（领星「销量统计列表 v2」）
     * 是 T+1，两者最新日期经常差一天。页面「当日 / 最新业务日」在订单语境下应以订单为准。
     */
    private LocalDate latestOrderDate() {
        LocalDate latest = jdbc.getJdbcTemplate().queryForObject(
                "select max(order_date) from wm_ads_order_record_valid where order_date is not null", LocalDate.class);
        return latest == null ? UsBusinessDate.usToday() : latest;
    }

    static LocalDate comparableLatestDate(LocalDate orderLatestDate, LocalDate adLatestDate, LocalDate usToday) {
        return java.util.stream.Stream.of(orderLatestDate, adLatestDate, usToday)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElse(usToday);
    }

    public DashboardPayload bootstrap() {
        Map<String, Object> bounds = jdbc.queryForMap("""
                select min(report_date) as first_date,
                       max(report_date) as latest_date
                  from wm_ads_lx_ad_report_raw
                 where report_level = 'AD_ITEM'
                """, new MapSqlParameterSource());
        LocalDate latestDate = localDate(bounds.get("latest_date"));
        LocalDate firstDate = localDate(bounds.get("first_date"));
        if (latestDate == null) {
            latestDate = UsBusinessDate.usToday();
        }
        if (firstDate == null) {
            firstDate = latestDate;
        }
        LocalDate orderLatestDate = latestOrderDate();
        Meta meta = new Meta(latestDate, firstDate, 0,
                new SourceRows(0, 0, 0, 0), "Bootstrap metadata only; no customer PII is exposed.",
                orderLatestDate, latestDate,
                comparableLatestDate(orderLatestDate, latestDate, UsBusinessDate.visibleSalesDate()));
        Defaults defaults = new Defaults(7, 5.0, 1.5, 20, 10.0, 10);
        return new DashboardPayload(meta, defaults, List.of(), List.of(), List.of());
    }

    /**
     * 暴露原始广告活动日行（每天 × 活动 × 广告类型一条），供决策服务在窗口内二次聚合。
     */
    public List<CampaignDailyRow> campaignDaily() {
        return loadCampaignDaily();
    }

    public List<Recommendation> recommendations(
            int days,
            double targetRoas,
            double floorRoas,
            int minClicks,
            double minSpend
    ) {
        DashboardPayload payload = load();
        LocalDate latestAdDate = payload.meta().adLatestDate() == null
                ? payload.meta().latestDate()
                : payload.meta().adLatestDate();
        LocalDate start = latestAdDate.minusDays(Math.max(days, 1) - 1L);
        return recommendations(payload, start, latestAdDate, targetRoas, floorRoas, minClicks, minSpend);
    }

    public List<Recommendation> recommendations(
            LocalDate startDate,
            LocalDate endDate,
            double targetRoas,
            double floorRoas,
            int minClicks,
            double minSpend
    ) {
        if (startDate == null || endDate == null || startDate.isAfter(endDate)) {
            throw new IllegalArgumentException("请选择有效的分析日期范围。");
        }
        DashboardPayload payload = load();
        return recommendations(payload, startDate, endDate, targetRoas, floorRoas, minClicks, minSpend);
    }

    private List<Recommendation> recommendations(
            DashboardPayload payload,
            LocalDate startDate,
            LocalDate endDate,
            double targetRoas,
            double floorRoas,
            int minClicks,
            double minSpend
    ) {
        Map<String, MutableCampaign> groups = new LinkedHashMap<>();
        payload.campaignDaily().stream()
                .filter(row -> !row.date().isBefore(startDate) && !row.date().isAfter(endDate))
                .forEach(row -> groups.computeIfAbsent(row.campaign(), ignored -> new MutableCampaign(row))
                        .add(row));
        return groups.values().stream()
                .map(item -> item.recommend(targetRoas, floorRoas, minClicks, minSpend))
                .sorted(Comparator.comparingInt(Recommendation::priority).thenComparing(Recommendation::campaign))
                .toList();
    }

    private List<SkuDailyRow> loadSkuDaily() {
        Set<String> validParents = loadValidParentSkus();
        boolean filterByProductLibrary = !validParents.isEmpty();
        List<Map<String, Object>> orders = jdbc.queryForList("""
                select orders.order_date as report_date,
                       coalesce(orders.parent_sku, orders.sku) as parent_sku,
                       sum(orders.quantity) as orders,
                       sum(orders.quantity * orders.item_cost) as revenue
                  from wm_ads_order_record_valid orders
                  join wm_ads_import_file file on file.id = orders.import_file_id and file.active = true
                 where orders.order_date is not null
                 group by orders.order_date, coalesce(orders.parent_sku, orders.sku)
                """, new MapSqlParameterSource());
        List<Map<String, Object>> ads = jdbc.queryForList("""
                select ads.report_date as report_date,
                       coalesce(ads.parent_sku, '未映射SKU') as parent_sku,
                       sum(ads.impressions) as impressions,
                       sum(ads.clicks) as clicks,
                       sum(ads.ad_spend) as spend,
                       sum(ads.attributed_orders) as ad_orders,
                       sum(ads.attributed_sales) as ad_sales
                  from wm_ads_lx_ad_report ads
                 where ads.report_level = 'AD_ITEM'
                 group by ads.report_date, coalesce(ads.parent_sku, '未映射SKU')
                """, new MapSqlParameterSource());
        Map<SkuDate, MutableSku> result = new HashMap<>();
        orders.forEach(row -> {
            String parentSku = text(row, "parent_sku");
            if (filterByProductLibrary && !validParents.contains(upper(parentSku))) {
                return;
            }
            SkuDate key = new SkuDate(date(row, "report_date"), parentSku);
            MutableSku item = result.computeIfAbsent(key, MutableSku::new);
            item.orders = decimal(row, "orders");
            item.revenue = decimal(row, "revenue");
        });
        ads.forEach(row -> {
            String parentSku = text(row, "parent_sku");
            if (filterByProductLibrary && !validParents.contains(upper(parentSku))) {
                return;
            }
            SkuDate key = new SkuDate(date(row, "report_date"), parentSku);
            MutableSku item = result.computeIfAbsent(key, MutableSku::new);
            item.impressions = decimal(row, "impressions");
            item.clicks = decimal(row, "clicks");
            item.spend = decimal(row, "spend");
            item.adOrders = decimal(row, "ad_orders");
            item.adSales = decimal(row, "ad_sales");
        });
        return result.values().stream()
                .map(MutableSku::toRow)
                .sorted(Comparator.comparing(SkuDailyRow::date).thenComparing(SkuDailyRow::sku))
                .toList();
    }

    /**
     * 产品库（wm_ads_product_record，current_version=1）中有效的父 SKU 集合。
     * 仅当该集合非空时才启用过滤：产品库为空时兜底显示全部，避免误杀。
     */
    private Set<String> loadValidParentSkus() {
        Set<String> parents = new LinkedHashSet<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select distinct upper(parent_sku) as parent_sku
                  from wm_ads_product_record
                 where current_version = true
                   and parent_sku is not null
                   and parent_sku <> ''
                """, new MapSqlParameterSource())) {
            String value = text(row, "parent_sku");
            if (value != null && !value.isBlank()) {
                parents.add(value);
            }
        }
        return parents;
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private List<CampaignDailyRow> loadCampaignDaily() {
        return jdbc.queryForList("""
                select ads.report_date as report_date,
                       ads.campaign_name as campaign_name,
                       max(ads.campaign_id) as campaign_id,
                       case
                           when ads.ad_type = 'SP' and lower(coalesce(ads.targeting_type, '')) = 'auto' then '自动广告'
                           when ads.ad_type = 'SP' then '手动广告'
                           when ads.ad_type = 'SB' then 'SB 品牌广告'
                           else 'SV 视频广告'
                       end as ad_type,
                       coalesce(max(ads.parent_sku), '未映射SKU') as parent_sku,
                       sum(ads.impressions) as impressions,
                       sum(ads.clicks) as clicks,
                       sum(ads.ad_spend) as spend,
                       sum(ads.attributed_orders) as ad_orders,
                       sum(ads.attributed_sales) as ad_sales,
                       case when sum(ads.clicks) > 0
                            then sum(ads.ad_spend) / sum(ads.clicks)
                            else avg(ads.bid)
                       end as average_bid,
                       count(distinct ads.item_id) as listed_products
                  from wm_ads_lx_ad_report ads
                 where ads.report_level = 'AD_ITEM'
                 group by ads.report_date, ads.campaign_name, ads.ad_type, ads.targeting_type
                """, new MapSqlParameterSource()).stream().map(row -> new CampaignDailyRow(
                        date(row, "report_date"),
                        text(row, "campaign_name"),
                        text(row, "campaign_id"),
                        text(row, "ad_type"),
                        text(row, "parent_sku"),
                        number(row, "impressions"),
                        number(row, "clicks"),
                        number(row, "spend"),
                        number(row, "ad_orders"),
                        number(row, "ad_sales"),
                        number(row, "average_bid"),
                        decimal(row, "listed_products").intValue()
                ))
                .sorted(Comparator.comparing(CampaignDailyRow::date).thenComparing(CampaignDailyRow::campaign))
                .toList();
    }

    private long count(String sql) {
        Long result = jdbc.getJdbcTemplate().queryForObject(sql, Long.class);
        return result == null ? 0L : result;
    }

    private static LocalDate date(Map<String, Object> row, String key) {
        return localDate(row.get(key));
    }

    private static LocalDate localDate(Object value) {
        if (value instanceof LocalDate localDate) {
            return localDate;
        }
        if (value instanceof Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return value == null ? null : LocalDate.parse(String.valueOf(value));
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

    private static double number(Map<String, Object> row, String key) {
        return decimal(row, key).doubleValue();
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    public record DashboardPayload(
            Meta meta,
            Defaults defaults,
            List<DailyRow> daily,
            List<SkuDailyRow> skuDaily,
            List<CampaignDailyRow> campaignDaily
    ) {
    }

    public record Meta(
            LocalDate latestDate,
            LocalDate firstDate,
            long importedFiles,
            SourceRows sourceRows,
            String privacy,
            /** 订单口径最新业务日。广告报表是 T+0、订单销量统计是 T+1，两者常差一天。 */
            LocalDate orderLatestDate,
            /** 广告报表最新业务日，仅供广告诊断等广告单源页面使用。 */
            LocalDate adLatestDate,
            /** 订单、广告和美西当前日共同具备的最后可比业务日。 */
            LocalDate analysisLatestDate
    ) {
    }

    public record SourceRows(long daily, long skuDaily, long products, long inventory) {
    }

    public record Defaults(
            int periodDays,
            double targetRoas,
            double floorRoas,
            int minClicks,
            double minSpend,
            int scalePct
    ) {
    }

    public record DailyRow(
            LocalDate date,
            double orders,
            double revenue,
            double impressions,
            double clicks,
            double spend,
            double adOrders,
            double naturalOrders,
            double adSales
    ) {
    }

    public record SkuDailyRow(
            LocalDate date,
            String sku,
            double orders,
            double revenue,
            double impressions,
            double clicks,
            double spend,
            double adOrders,
            double naturalOrders,
            double adSales
    ) {
    }

    public record CampaignDailyRow(
            LocalDate date,
            String campaign,
            String campaignId,
            String type,
            String sku,
            double impressions,
            double clicks,
            double spend,
            double adOrders,
            double adSales,
            double avgBid,
            int listedProducts
    ) {
    }

    public record Recommendation(
            int priority,
            String action,
            String label,
            String campaign,
            String campaignId,
            String type,
            String sku,
            double spend,
            double adSales,
            double adOrders,
            double clicks,
            double roas,
            String reason
    ) {
    }

    private static final class MutableDaily {
        private final LocalDate date;
        private BigDecimal orders = ZERO;
        private BigDecimal revenue = ZERO;
        private BigDecimal impressions = ZERO;
        private BigDecimal clicks = ZERO;
        private BigDecimal spend = ZERO;
        private BigDecimal adOrders = ZERO;
        private BigDecimal adSales = ZERO;

        private MutableDaily(LocalDate date) {
            this.date = date;
        }

        private DailyRow toRow() {
            return new DailyRow(
                    date,
                    orders.doubleValue(),
                    revenue.doubleValue(),
                    impressions.doubleValue(),
                    clicks.doubleValue(),
                    spend.doubleValue(),
                    adOrders.doubleValue(),
                    orders.subtract(adOrders).max(ZERO).doubleValue(),
                    adSales.doubleValue()
            );
        }
    }

    private record SkuDate(LocalDate date, String sku) {
    }

    private static final class MutableSku {
        private final SkuDate key;
        private BigDecimal orders = ZERO;
        private BigDecimal revenue = ZERO;
        private BigDecimal impressions = ZERO;
        private BigDecimal clicks = ZERO;
        private BigDecimal spend = ZERO;
        private BigDecimal adOrders = ZERO;
        private BigDecimal adSales = ZERO;

        private MutableSku(SkuDate key) {
            this.key = key;
        }

        private SkuDailyRow toRow() {
            return new SkuDailyRow(
                    key.date(),
                    key.sku(),
                    orders.doubleValue(),
                    revenue.doubleValue(),
                    impressions.doubleValue(),
                    clicks.doubleValue(),
                    spend.doubleValue(),
                    adOrders.doubleValue(),
                    orders.subtract(adOrders).max(ZERO).doubleValue(),
                    adSales.doubleValue()
            );
        }
    }

    private static final class MutableCampaign {
        private final String campaign;
        private final String campaignId;
        private final String type;
        private final String sku;
        private double spend;
        private double adSales;
        private double adOrders;
        private double clicks;

        private MutableCampaign(CampaignDailyRow row) {
            this.campaign = row.campaign();
            this.campaignId = row.campaignId();
            this.type = row.type();
            this.sku = row.sku();
        }

        private void add(CampaignDailyRow row) {
            spend += row.spend();
            adSales += row.adSales();
            adOrders += row.adOrders();
            clicks += row.clicks();
        }

        private Recommendation recommend(double targetRoas, double floorRoas, int minClicks, double minSpend) {
            double roas = spend == 0 ? 0 : adSales / spend;
            boolean enough = clicks >= minClicks && spend >= minSpend;
            String action;
            String label;
            String reason;
            int priority;
            if (!enough) {
                action = "collect";
                label = "数据不足";
                reason = "点击或花费未达到样本门槛，继续观察。";
                priority = 4;
            } else if (roas >= targetRoas && adOrders >= 3) {
                action = "scale";
                label = "建议放量";
                reason = "ROAS 达标且已有稳定广告订单，可小步增加预算。";
                priority = 2;
            } else if (roas < floorRoas || adOrders == 0) {
                action = "reduce";
                label = "建议降耗";
                reason = "达到样本门槛后效率仍偏低，建议降低出价或暂停低效目标。";
                priority = 1;
            } else {
                action = "optimize";
                label = "继续优化";
                reason = "已有足够数据但未达到放量阈值，建议优化商品、关键词或出价。";
                priority = 3;
            }
            return new Recommendation(
                    priority, action, label, campaign, campaignId, type, sku,
                    round(spend), round(adSales), round(adOrders), round(clicks), round(roas), reason
            );
        }
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP).doubleValue();
    }
}
