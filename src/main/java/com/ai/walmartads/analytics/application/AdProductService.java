package com.ai.walmartads.analytics.application;

import com.ai.walmartads.common.UsBusinessDate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 广告表现（按产品）：按 parent_sku 聚合领星 AD_ITEM 日事实（SP / SB / SV），
 * 输出每款曝光 / 点击 / 广告费 / 订单 / 归因销售，并派生 RoAS / ACOS / CTR / CPC / 转化率，
 * 对应参考报表「当天广告数据」子表（产品级广告表现）。
 */
@Service
public class AdProductService {

    private static final int SCALE = 4;

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductImageLookupService productImages;

    public AdProductService(NamedParameterJdbcTemplate jdbc, ProductImageLookupService productImages) {
        this.jdbc = jdbc;
        this.productImages = productImages;
    }

    public AdProductPayload load(int days) {
        return load(days, false);
    }

    public AdProductPayload load(int days, boolean latest) {
        int window = latest ? 1 : Math.max(1, Math.min(days, 365));
        LocalDate end = UsBusinessDate.capToVisibleSalesDate(latestAdDate());
        LocalDate start = end.minusDays(window - 1L);

        Map<String, Object> params = new HashMap<>();
        params.put("start", start);
        params.put("end", end);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select a.parent_sku as parent_sku,
                       sum(a.impressions) as impressions,
                       sum(a.clicks) as clicks,
                       sum(a.ad_spend) as spend,
                       sum(a.attributed_orders) as ad_orders,
                       sum(a.attributed_sales) as sales
                  from wm_ads_lx_ad_report a
                 where a.report_level = 'AD_ITEM'
                   and a.report_date >= :start
                   and a.report_date <= :end
                   and a.parent_sku is not null and a.parent_sku <> ''
                 group by a.parent_sku
                """, params);

        Map<String, String> imageByStyle = productImages.loadParentImages();
        List<AdProduct> products = rows.stream().map(row -> {
            String style = String.valueOf(row.get("parent_sku"));
            double impressions = num(row, "impressions");
            double clicks = num(row, "clicks");
            double spend = num(row, "spend");
            double orders = num(row, "ad_orders");
            double sales = num(row, "sales");
            double roas = spend > 0 ? sales / spend : 0;
            double acos = sales > 0 ? spend / sales : 0;
            double ctr = impressions > 0 ? clicks / impressions : 0;
            double cpc = clicks > 0 ? spend / clicks : 0;
            double conversionRate = clicks > 0 ? orders / clicks : 0;
            return new AdProduct(
                    style,
                    imageByStyle.getOrDefault(style, ""),
                    impressions, clicks, spend, orders, sales,
                    round(roas), round(acos), round(ctr), round(cpc), round(conversionRate));
        }).sorted(Comparator.comparingDouble(AdProduct::spend).reversed()).toList();

        double tImpressions = products.stream().mapToDouble(AdProduct::impressions).sum();
        double tClicks = products.stream().mapToDouble(AdProduct::clicks).sum();
        double tSpend = products.stream().mapToDouble(AdProduct::spend).sum();
        double tOrders = products.stream().mapToDouble(AdProduct::orders).sum();
        double tSales = products.stream().mapToDouble(AdProduct::sales).sum();
        AdProductSummary summary = new AdProductSummary(
                tImpressions, tClicks, tSpend, tOrders, tSales,
                round(tSpend > 0 ? tSales / tSpend : 0),
                round(tSales > 0 ? tSpend / tSales : 0),
                round(tImpressions > 0 ? tClicks / tImpressions : 0),
                round(tClicks > 0 ? tSpend / tClicks : 0),
                round(tClicks > 0 ? tOrders / tClicks : 0));

        return new AdProductPayload(start, end, window, latest, products, summary);
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(SCALE, RoundingMode.HALF_UP).doubleValue();
    }

    private static double num(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private LocalDate latestAdDate() {
        LocalDate latest = jdbc.getJdbcTemplate().queryForObject(
                "select max(report_date) from wm_ads_lx_ad_report where report_level = 'AD_ITEM'", LocalDate.class);
        return latest == null ? UsBusinessDate.visibleSalesDate() : latest;
    }

    public record AdProductPayload(
            LocalDate start,
            LocalDate end,
            int days,
            boolean latest,
            List<AdProduct> products,
            AdProductSummary summary
    ) {
    }

    public record AdProductSummary(
            double totalImpressions,
            double totalClicks,
            double totalSpend,
            double totalOrders,
            double totalSales,
            double roas,
            double acos,
            double ctr,
            double cpc,
            double conversionRate
    ) {
    }

    public record AdProduct(
            String style,
            String imageUrl,
            double impressions,
            double clicks,
            double spend,
            double orders,
            double sales,
            double roas,
            double acos,
            double ctr,
            double cpc,
            double conversionRate
    ) {
    }
}
