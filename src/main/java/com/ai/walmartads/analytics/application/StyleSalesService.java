package com.ai.walmartads.analytics.application;

import com.ai.walmartads.common.UsBusinessDate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 各款每日销量：按 (report_date, parent_sku) 聚合订单销量件数，输出日期轴、款号销量排行
 * 与 Top 款每日序列，对应参考报表「每日各品名销量」子表（行=日期，列=款号，值=销量件数）。
 */
@Service
public class StyleSalesService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final int TOP_STYLES = 12;

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductImageLookupService productImages;

    public StyleSalesService(NamedParameterJdbcTemplate jdbc, ProductImageLookupService productImages) {
        this.jdbc = jdbc;
        this.productImages = productImages;
    }

    public StyleSalesPayload load(int days) {
        int window = Math.max(1, Math.min(days, 365));
        LocalDate orderLatest = latestOrderDate();
        LocalDate adLatest = latestAdReportDate();
        LocalDate end = comparableLatestDate(orderLatest, adLatest, UsBusinessDate.visibleSalesDate());
        LocalDate start = end.minusDays(window - 1L);

        Map<String, Object> params = new HashMap<>();
        params.put("start", start);
        params.put("end", end);

        List<Map<String, Object>> rows = jdbc.queryForList("""
                select o.order_date as report_date,
                       o.parent_sku as parent_sku,
                       sum(o.quantity) as qty
                  from wm_ads_order_record_valid o
                  join wm_ads_import_file f on f.id = o.import_file_id and f.active = true
                 where o.order_date >= :start and o.order_date <= :end
                   and o.parent_sku is not null and o.parent_sku <> ''
                 group by o.order_date, o.parent_sku
                """, params);

        TreeMap<LocalDate, Map<String, BigDecimal>> byDate = new TreeMap<>();
        TreeMap<String, BigDecimal> totals = new TreeMap<>();
        for (Map<String, Object> row : rows) {
            LocalDate d = date(row, "report_date");
            String sku = String.valueOf(row.get("parent_sku"));
            BigDecimal qty = decimal(row, "qty");
            byDate.computeIfAbsent(d, k -> new HashMap<>()).merge(sku, qty, BigDecimal::add);
            totals.merge(sku, qty, BigDecimal::add);
        }

        List<LocalDate> dates = new ArrayList<>(byDate.keySet());
        Map<String, String> imageByStyle = productImages.loadParentImages();
        List<StyleRank> ranking = totals.entrySet().stream()
                .map(e -> new StyleRank(e.getKey(), imageByStyle.getOrDefault(e.getKey(), ""), e.getValue().doubleValue(), 0))
                .sorted(Comparator.comparingDouble(StyleRank::totalQty).reversed())
                .toList();
        double grand = ranking.stream().mapToDouble(StyleRank::totalQty).sum();
        List<String> topStyles = ranking.stream().limit(TOP_STYLES).map(StyleRank::style).toList();
        // 全部款号的每日序列（供透视表还原原始「每日各品名销量」列布局），topStyles 仍用于图表
        List<String> allStyles = ranking.stream().map(StyleRank::style).toList();

        Map<String, List<Double>> series = new LinkedHashMap<>();
        for (String sku : allStyles) {
            List<Double> seriesRow = new ArrayList<>();
            for (LocalDate d : dates) {
                seriesRow.add(byDate.get(d).getOrDefault(sku, ZERO).doubleValue());
            }
            series.put(sku, seriesRow);
        }

        List<StyleRank> rankingWithShare = ranking.stream()
                .map(r -> new StyleRank(r.style(), r.imageUrl(), r.totalQty(), grand > 0 ? r.totalQty() / grand : 0))
                .toList();

        return new StyleSalesPayload(
                start, end, window,
                dates.stream().map(LocalDate::toString).toList(),
                rankingWithShare, topStyles, series, grand);
    }

    /**
     * 订单最新业务日：与经营驾驶舱 / 销量分析对齐，统一使用 order_date（T+1），
     * 而非 report_date。子块与上方每日销售数量总览须保持同一 end 基准。
     */
    private LocalDate latestOrderDate() {
        LocalDate latest = jdbc.getJdbcTemplate().queryForObject(
                "select max(order_date) from wm_ads_order_record_valid where order_date is not null", LocalDate.class);
        return latest == null ? UsBusinessDate.usToday() : latest;
    }

    private LocalDate latestAdReportDate() {
        LocalDate latest = jdbc.getJdbcTemplate().queryForObject(
                "select max(report_date) from wm_ads_lx_ad_report_raw where report_level = 'AD_ITEM'", LocalDate.class);
        return latest == null ? UsBusinessDate.usToday() : latest;
    }

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

    public record StyleSalesPayload(
            LocalDate start,
            LocalDate end,
            int days,
            List<String> dates,
            List<StyleRank> ranking,
            List<String> topStyles,
            Map<String, List<Double>> series,
            double grandTotal
    ) {
    }

    public record StyleRank(
            String style,
            String imageUrl,
            double totalQty,
            double share
    ) {
    }
}
