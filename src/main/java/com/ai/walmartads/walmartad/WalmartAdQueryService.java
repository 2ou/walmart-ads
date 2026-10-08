package com.ai.walmartads.walmartad;

import com.ai.walmartads.common.UsBusinessDate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

@Service
public class WalmartAdQueryService {

    private static final Set<String> LEVELS = Set.of("CAMPAIGN", "AD_GROUP", "AD_ITEM", "KEYWORD");
    private static final Set<String> AD_TYPES = Set.of("SP", "SB", "SV");
    private static final Set<String> SLICE_TYPES = Set.of("PAGE_TYPE", "PLATFORM");

    private final NamedParameterJdbcTemplate jdbc;

    public WalmartAdQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Overview overview(LocalDate requestedStart, LocalDate requestedEnd, String requestedAdType) {
        LocalDate latest = latestDate();
        LocalDate end = requestedEnd == null ? latest : requestedEnd;
        LocalDate start = requestedStart == null ? end.minusDays(6) : requestedStart;
        validateDates(start, end);
        String adType = adType(requestedAdType);
        MapSqlParameterSource params = range(start, end, adType);
        String adFilter = adFilter(adType);

        OverviewAggregation aggregation = aggregateOverview(params, adFilter);

        List<Map<String, Object>> coverage = jdbc.queryForList("""
                select ad_type, report_level, count(*) as rows_count,
                       count(distinct report_date) as day_count,
                       max(report_date) as latest_date
                  from wm_ads_lx_ad_report
                 where report_date between :start and :end
                """ + adFilter + " group by ad_type, report_level order by ad_type, report_level", params);

        List<Map<String, Object>> sliceCoverage = jdbc.queryForList("""
                select ad_type, slice_type, count(*) as rows_count,
                       count(distinct report_date) as day_count,
                       max(report_date) as latest_date
                  from wm_ads_lx_ad_slice
                 where report_date between :start and :end
                """ + adFilter + " group by ad_type, slice_type order by ad_type, slice_type", params);

        List<Map<String, Object>> sourceCoverage = jdbc.queryForList("""
                select source, count(*) as rows_count,
                       count(distinct report_date) as day_count,
                       min(report_date) as first_date,
                       max(report_date) as latest_date,
                       count(distinct case when report_level = 'AD_ITEM' then item_id end) as item_count
                  from wm_ads_lx_ad_report
                 where report_date between :start and :end
                """ + adFilter + " group by source order by first_date", params);

        List<Map<String, Object>> sourceHistory = jdbc.getJdbcTemplate().queryForList("""
                select source, count(*) as rows_count,
                       count(distinct report_date) as day_count,
                       min(report_date) as first_date,
                       max(report_date) as latest_date,
                       count(distinct case when report_level = 'AD_ITEM' then item_id end) as item_count
                  from wm_ads_lx_ad_report
                 group by source
                 order by first_date
                """);

        Map<String, Object> dataQuality = jdbc.queryForMap("""
                select count(*) as rows_count,
                       count(distinct report_date) as day_count,
                       count(distinct case when report_level = 'AD_ITEM' then item_id end) as item_count,
                       sum(case when report_level = 'AD_ITEM' then 1 else 0 end) as item_rows,
                       sum(case when report_level = 'AD_ITEM' and (sku is null or trim(sku) = '') then 1 else 0 end) as missing_sku_rows,
                       sum(case when report_level = 'AD_ITEM' and (parent_sku is null or trim(parent_sku) = '') then 1 else 0 end) as missing_parent_sku_rows,
                       max(synced_at) as latest_synced_at
                  from wm_ads_lx_ad_report                 where report_date between :start and :end
                """ + adFilter, params);

        return new Overview(start, end, latest, adType == null ? "ALL" : adType,
                aggregation.summary(), aggregation.typeSummary(), aggregation.trend(),
                coverage, sliceCoverage, sourceCoverage, sourceHistory, dataQuality,
                aggregation.campaigns());
    }

    public PagedRows reports(
            String requestedLevel,
            LocalDate requestedStart,
            LocalDate requestedEnd,
            String requestedAdType,
            String requestedSearch,
            int requestedPage,
            int requestedSize
    ) {
        String level = normalize(requestedLevel, "report level");
        if (!LEVELS.contains(level)) {
            throw new IllegalArgumentException("Unsupported report level: " + requestedLevel);
        }
        LocalDate end = requestedEnd == null ? latestDate() : requestedEnd;
        LocalDate start = requestedStart == null ? end.minusDays(6) : requestedStart;
        validateDates(start, end);
        String adType = adType(requestedAdType);
        int page = Math.max(1, requestedPage);
        int size = Math.max(1, Math.min(requestedSize, 500));
        MapSqlParameterSource params = range(start, end, adType)
                .addValue("level", level)
                .addValue("search", search(requestedSearch))
                .addValue("limit", size)
                .addValue("offset", (page - 1) * size);
        String filter = adFilter(adType);
        String searchFilter = searchFilter(requestedSearch);

        Long total = jdbc.queryForObject("""
                select count(*) from wm_ads_lx_ad_report
                 where report_level = :level and report_date between :start and :end
                """ + filter + searchFilter, params, Long.class);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, report_date, ad_type, report_level,
                       source, attribution_days,
                       campaign_id, campaign_name, campaign_type, campaign_status, targeting_type,
                       bidding_strategy, daily_budget, target_roas,
                       ad_group_id, ad_group_name, ad_group_status,
                       item_id, ad_item_id, sku, parent_sku, ad_name, ad_status, review_status,
                       review_reason,
                       keyword_id, keyword_text, keyword_state, keyword_status, match_type,
                       bid, suggested_bid, impressions, clicks, ad_spend,
                       attributed_orders, attributed_sales, attributed_units,
                       advertised_sku_sales, advertised_sku_units,
                       other_sku_sales, other_sku_units,
                       ntb_orders, ntb_revenue, ntb_units,
                       cpc, ctr, cvr, acos, roas, aov, cpa, synced_at
                  from wm_ads_lx_ad_report
                 where report_level = :level and report_date between :start and :end
                """ + filter + searchFilter
                + " order by report_date desc, ad_spend desc, id desc limit :limit offset :offset", params);
        return new PagedRows(start, end, adType == null ? "ALL" : adType, level,
                page, size, total == null ? 0 : total, rows);
    }

    public List<Map<String, Object>> slices(
            String requestedSliceType,
            LocalDate requestedStart,
            LocalDate requestedEnd,
            String requestedAdType
    ) {
        String sliceType = normalize(requestedSliceType, "slice type");
        if (!SLICE_TYPES.contains(sliceType)) {
            throw new IllegalArgumentException("Unsupported slice type: " + requestedSliceType);
        }
        LocalDate end = requestedEnd == null ? latestDate() : requestedEnd;
        LocalDate start = requestedStart == null ? end.minusDays(6) : requestedStart;
        validateDates(start, end);
        String adType = adType(requestedAdType);
        MapSqlParameterSource params = range(start, end, adType).addValue("sliceType", sliceType);
        return jdbc.queryForList("""
                select ad_type, slice_type, dimension_value,
                       sum(impressions) as impressions,
                       sum(clicks) as clicks,
                       sum(ad_spend) as spend,
                       sum(attributed_orders) as orders,
                       sum(attributed_sales) as sales,
                       sum(attributed_units) as units,
                       case when sum(ad_spend) > 0 then sum(attributed_sales) / sum(ad_spend) else 0 end as roas,
                       case when sum(attributed_sales) > 0 then sum(ad_spend) / sum(attributed_sales) else 0 end as acos,
                       case when sum(impressions) > 0 then sum(clicks) / sum(impressions) else 0 end as ctr,
                       case when sum(clicks) > 0 then sum(ad_spend) / sum(clicks) else 0 end as cpc,
                       case when sum(clicks) > 0 then sum(attributed_orders) / sum(clicks) else 0 end as cvr,
                       sum(advertised_sku_sales) as advertised_sku_sales,
                       sum(advertised_sku_units) as advertised_sku_units,
                       sum(other_sku_sales) as other_sku_sales,
                       sum(other_sku_units) as other_sku_units,
                       sum(ntb_orders) as ntb_orders,
                       sum(ntb_revenue) as ntb_revenue,
                       sum(ntb_units) as ntb_units
                  from wm_ads_lx_ad_slice
                 where slice_type = :sliceType and report_date between :start and :end
                """ + adFilter(adType)
                + " group by ad_type, slice_type, dimension_value order by spend desc", params);
    }

    /**
     * Reads the JSON-bearing AD_ITEM fact rows once, at campaign/day grain, then derives
     * the page summary, type cards, trend, and campaign ranking in memory.  The previous
     * implementation scanned the same fact window four times and dominated page latency.
     */
    private OverviewAggregation aggregateOverview(MapSqlParameterSource params, String adFilter) {
        List<Map<String, Object>> dailyCampaigns = jdbc.queryForList("""
                select report_date, ad_type, campaign_id,
                       max(campaign_name) as campaign_name,
                       max(campaign_status) as campaign_status,
                       max(targeting_type) as targeting_type,
                       count(*) as rows_count,
                       sum(impressions) as impressions,
                       sum(clicks) as clicks,
                       sum(ad_spend) as spend,
                       sum(attributed_orders) as orders,
                       sum(attributed_sales) as sales,
                       sum(attributed_units) as units,
                       sum(advertised_sku_sales) as advertised_sku_sales,
                       sum(advertised_sku_units) as advertised_sku_units,
                       sum(other_sku_sales) as other_sku_sales,
                       sum(other_sku_units) as other_sku_units,
                       sum(ntb_orders) as ntb_orders,
                       sum(ntb_revenue) as ntb_revenue,
                       sum(ntb_units) as ntb_units
                  from wm_ads_lx_ad_report                 where report_level = 'AD_ITEM'
                   and report_date between :start and :end
                """ + adFilter
                + " group by report_date, ad_type, campaign_id order by report_date, ad_type, campaign_id", params);

        MetricAccumulator summary = new MetricAccumulator();
        Map<String, MetricAccumulator> byType = new TreeMap<>();
        Map<String, MetricAccumulator> byTrend = new LinkedHashMap<>();
        Map<String, CampaignAccumulator> byCampaign = new LinkedHashMap<>();
        for (Map<String, Object> row : dailyCampaigns) {
            String date = String.valueOf(row.get("report_date"));
            String type = string(row.get("ad_type"));
            String campaignId = string(row.get("campaign_id"));
            summary.add(row, date);
            byType.computeIfAbsent(type, ignored -> new MetricAccumulator()).add(row, date);
            byTrend.computeIfAbsent(date + "|" + type, ignored -> new MetricAccumulator()).add(row, date);
            byCampaign.computeIfAbsent(type + "|" + campaignId,
                            ignored -> new CampaignAccumulator(type, campaignId))
                    .add(row, date);
        }

        List<Map<String, Object>> typeSummary = new ArrayList<>();
        byType.forEach((type, metrics) -> {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("ad_type", type);
            output.putAll(metrics.toMap());
            typeSummary.add(output);
        });

        List<Map<String, Object>> trend = new ArrayList<>();
        byTrend.forEach((key, metrics) -> {
            String[] parts = key.split("\\|", 2);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("report_date", parts[0]);
            output.put("ad_type", parts[1]);
            output.putAll(metrics.toMap());
            trend.add(output);
        });

        List<Map<String, Object>> campaigns = byCampaign.values().stream()
                .map(CampaignAccumulator::toMap)
                .sorted(Comparator.comparing(
                        row -> decimal(row.get("spend")), Comparator.reverseOrder()))
                .limit(100)
                .toList();
        return new OverviewAggregation(summary.toMap(), typeSummary, trend, campaigns);
    }

    private static final class MetricAccumulator {
        private final Set<String> dates = new HashSet<>();
        private long rowsCount;
        private long impressions;
        private long clicks;
        private BigDecimal spend = BigDecimal.ZERO;
        private BigDecimal orders = BigDecimal.ZERO;
        private BigDecimal sales = BigDecimal.ZERO;
        private BigDecimal units = BigDecimal.ZERO;
        private BigDecimal advertisedSkuSales = BigDecimal.ZERO;
        private BigDecimal advertisedSkuUnits = BigDecimal.ZERO;
        private BigDecimal otherSkuSales = BigDecimal.ZERO;
        private BigDecimal otherSkuUnits = BigDecimal.ZERO;
        private BigDecimal ntbOrders = BigDecimal.ZERO;
        private BigDecimal ntbRevenue = BigDecimal.ZERO;
        private BigDecimal ntbUnits = BigDecimal.ZERO;

        private void add(Map<String, Object> row, String date) {
            dates.add(date);
            rowsCount += decimal(row.get("rows_count")).longValue();
            impressions += decimal(row.get("impressions")).longValue();
            clicks += decimal(row.get("clicks")).longValue();
            spend = spend.add(decimal(row.get("spend")));
            orders = orders.add(decimal(row.get("orders")));
            sales = sales.add(decimal(row.get("sales")));
            units = units.add(decimal(row.get("units")));
            advertisedSkuSales = advertisedSkuSales.add(decimal(row.get("advertised_sku_sales")));
            advertisedSkuUnits = advertisedSkuUnits.add(decimal(row.get("advertised_sku_units")));
            otherSkuSales = otherSkuSales.add(decimal(row.get("other_sku_sales")));
            otherSkuUnits = otherSkuUnits.add(decimal(row.get("other_sku_units")));
            ntbOrders = ntbOrders.add(decimal(row.get("ntb_orders")));
            ntbRevenue = ntbRevenue.add(decimal(row.get("ntb_revenue")));
            ntbUnits = ntbUnits.add(decimal(row.get("ntb_units")));
        }

        private Map<String, Object> toMap() {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("rows_count", rowsCount);
            output.put("day_count", dates.size());
            output.put("impressions", impressions);
            output.put("clicks", clicks);
            output.put("spend", spend);
            output.put("orders", orders);
            output.put("sales", sales);
            output.put("units", units);
            output.put("advertised_sku_sales", advertisedSkuSales);
            output.put("advertised_sku_units", advertisedSkuUnits);
            output.put("other_sku_sales", otherSkuSales);
            output.put("other_sku_units", otherSkuUnits);
            output.put("ntb_orders", ntbOrders);
            output.put("ntb_revenue", ntbRevenue);
            output.put("ntb_units", ntbUnits);
            output.put("roas", divide(sales, spend));
            output.put("acos", divide(spend, sales));
            output.put("ctr", divide(BigDecimal.valueOf(clicks), BigDecimal.valueOf(impressions)));
            output.put("cpc", divide(spend, BigDecimal.valueOf(clicks)));
            output.put("cvr", divide(orders, BigDecimal.valueOf(clicks)));
            output.put("aov", divide(sales, orders));
            output.put("cpa", divide(spend, orders));
            return output;
        }
    }

    private static final class CampaignAccumulator {
        private final String adType;
        private final String campaignId;
        private final MetricAccumulator metrics = new MetricAccumulator();
        private String campaignName;
        private String campaignStatus;
        private String targetingType;

        private CampaignAccumulator(String adType, String campaignId) {
            this.adType = adType;
            this.campaignId = campaignId;
        }

        private void add(Map<String, Object> row, String date) {
            metrics.add(row, date);
            campaignName = prefer(row.get("campaign_name"), campaignName);
            campaignStatus = prefer(row.get("campaign_status"), campaignStatus);
            targetingType = prefer(row.get("targeting_type"), targetingType);
        }

        private Map<String, Object> toMap() {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("ad_type", adType);
            output.put("campaign_id", campaignId);
            output.put("campaign_name", campaignName);
            output.put("campaign_status", campaignStatus);
            output.put("targeting_type", targetingType);
            output.putAll(metrics.toMap());
            return output;
        }
    }

    private static String prefer(Object candidate, String fallback) {
        String value = string(candidate);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        return denominator == null || denominator.signum() == 0
                ? BigDecimal.ZERO
                : numerator.divide(denominator, 8, RoundingMode.HALF_UP);
    }

    private record OverviewAggregation(
            Map<String, Object> summary,
            List<Map<String, Object>> typeSummary,
            List<Map<String, Object>> trend,
            List<Map<String, Object>> campaigns
    ) { }

    private LocalDate latestDate() {
        LocalDate latest = jdbc.getJdbcTemplate().queryForObject(
                "select max(report_date) from wm_ads_lx_ad_report", LocalDate.class);
        return latest == null ? UsBusinessDate.usToday() : latest;
    }

    private static MapSqlParameterSource range(LocalDate start, LocalDate end, String adType) {
        return new MapSqlParameterSource().addValue("start", start).addValue("end", end).addValue("adType", adType);
    }

    private static String adFilter(String adType) {
        return adType == null ? "" : " and ad_type = :adType";
    }

    private static String searchFilter(String value) {
        return value == null || value.isBlank() ? "" : """
                 and (
                       lower(coalesce(campaign_name, '')) like :search
                    or lower(coalesce(campaign_id, '')) like :search
                    or lower(coalesce(ad_group_name, '')) like :search
                    or lower(coalesce(ad_group_id, '')) like :search
                    or lower(coalesce(sku, '')) like :search
                    or lower(coalesce(parent_sku, '')) like :search
                    or lower(coalesce(item_id, '')) like :search
                    or lower(coalesce(ad_name, '')) like :search
                    or lower(coalesce(keyword_text, '')) like :search
                    or lower(coalesce(keyword_id, '')) like :search
                 )
                """;
    }

    private static String search(String value) {
        return value == null || value.isBlank()
                ? null
                : "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
    }

    private static String adType(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!AD_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported ad type: " + value);
        }
        return normalized;
    }

    private static String normalize(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static void validateDates(LocalDate start, LocalDate end) {
        if (start == null || end == null || start.isAfter(end)) {
            throw new IllegalArgumentException("Invalid advertising date range");
        }
        if (ChronoUnit.DAYS.between(start, end) > 3650) {
            throw new IllegalArgumentException("Advertising date range is too large");
        }
    }

    public record Overview(
            LocalDate startDate,
            LocalDate endDate,
            LocalDate latestDate,
            String adType,
            Map<String, Object> summary,
            List<Map<String, Object>> typeSummary,
            List<Map<String, Object>> trend,
            List<Map<String, Object>> coverage,
            List<Map<String, Object>> sliceCoverage,
            List<Map<String, Object>> sourceCoverage,
            List<Map<String, Object>> sourceHistory,
            Map<String, Object> dataQuality,
            List<Map<String, Object>> campaigns
    ) { }

    public record PagedRows(
            LocalDate startDate,
            LocalDate endDate,
            String adType,
            String level,
            int page,
            int size,
            long total,
            List<Map<String, Object>> rows
    ) { }
}
