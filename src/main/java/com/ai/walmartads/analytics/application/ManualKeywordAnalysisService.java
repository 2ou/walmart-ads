package com.ai.walmartads.analytics.application;

import com.ai.walmartads.common.UsBusinessDate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Decision-oriented analysis of LingXing manual-ad keyword performance. */
@Service
public class ManualKeywordAnalysisService {

    private static final Set<String> AD_TYPES = Set.of("SP", "SB", "SV");
    private static final Pattern PARENT_SKU = Pattern.compile("(PNK\\d+)", Pattern.CASE_INSENSITIVE);
    private static final DecisionThresholds DEFAULT_THRESHOLDS =
            new DecisionThresholds(5.0, 1.5, 20, 10.0);

    private final NamedParameterJdbcTemplate jdbc;
    private final CampaignDecisionConfigRepository configRepository;

    public ManualKeywordAnalysisService(
            NamedParameterJdbcTemplate jdbc,
            CampaignDecisionConfigRepository configRepository
    ) {
        this.jdbc = jdbc;
        this.configRepository = configRepository;
    }

    public Analysis analyze(LocalDate requestedStart, LocalDate requestedEnd, String requestedAdType) {
        LocalDate latestDate = latestDate();
        LocalDate endDate = requestedEnd == null ? latestDate : requestedEnd;
        LocalDate startDate = requestedStart == null ? endDate.minusDays(6) : requestedStart;
        validateDates(startDate, endDate);
        String adType = normalizeAdType(requestedAdType);
        DecisionThresholds thresholds = thresholds();

        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("start", startDate)
                .addValue("end", endDate)
                .addValue("adType", adType);
        String adFilter = adType == null ? "" : " and ad_type = :adType";
        List<Map<String, Object>> sourceRows = jdbc.queryForList("""
                select report_date, advertiser_id, ad_type, entity_key,
                       campaign_id, campaign_name, campaign_status,
                       ad_group_id, ad_group_name, ad_group_status,
                       keyword_id, keyword_text, keyword_state, keyword_status, match_type,
                       bid, suggested_bid,
                       impressions, clicks, ad_spend, attributed_sales,
                       attributed_orders, attributed_units
                  from wm_ads_lx_ad_report                 where report_level = 'KEYWORD'
                   and targeting_type = 'manual'
                   and report_date between :start and :end
                """ + adFilter + " order by report_date, id", parameters);

        Map<String, MutableKeyword> grouped = new LinkedHashMap<>();
        Map<LocalDate, MutableMetrics> trend = new LinkedHashMap<>();
        for (Map<String, Object> source : sourceRows) {
            LocalDate reportDate = date(source.get("report_date"));
            String key = longValue(source.get("advertiser_id")) + "|" + text(source.get("ad_type"))
                    + "|" + text(source.get("entity_key"));
            grouped.computeIfAbsent(key, ignored -> new MutableKeyword()).add(source, reportDate);
            trend.computeIfAbsent(reportDate, ignored -> new MutableMetrics()).add(source);
        }

        List<KeywordRow> keywords = grouped.values().stream()
                .map(keyword -> keyword.toRow(thresholds))
                .sorted(Comparator.comparing(KeywordRow::spend).reversed()
                        .thenComparing(KeywordRow::keywordText, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();
        List<MatchSummary> matchTypes = List.of("exact", "phrase", "broad").stream()
                .map(type -> MatchSummary.from(type, keywords))
                .toList();
        List<DailyTrend> dailyTrend = trend.entrySet().stream()
                .map(entry -> entry.getValue().toTrend(entry.getKey()))
                .toList();
        List<CampaignOption> campaigns = keywords.stream()
                .collect(LinkedHashMap<String, CampaignOption>::new,
                        (map, row) -> map.putIfAbsent(row.adType() + "|" + row.campaignId(),
                                new CampaignOption(row.adType(), row.campaignId(), row.campaignName(), row.parentSku())),
                        Map::putAll)
                .values().stream()
                .sorted(Comparator.comparing(CampaignOption::campaignName,
                        Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                .toList();

        return new Analysis(
                new Meta(startDate, endDate, latestDate, adType == null ? "ALL" : adType,
                        sourceRows.size(), keywords.size()),
                Summary.from(keywords),
                thresholds,
                matchTypes,
                dailyTrend,
                campaigns,
                keywords
        );
    }

    private DecisionThresholds thresholds() {
        return configRepository.findById("手动广告")
                .map(config -> new DecisionThresholds(
                        config.getTargetRoas(), config.getFloorRoas(),
                        config.getMinClicks(), config.getMinSpend()))
                .orElse(DEFAULT_THRESHOLDS);
    }

    static String decide(double spend, double sales, double orders, long clicks, DecisionThresholds thresholds) {
        if (clicks < thresholds.minClicks() || spend < thresholds.minSpend()) {
            return "collect";
        }
        double roas = ratio(sales, spend);
        if (roas >= thresholds.targetRoas() && orders >= 3) {
            return "scale";
        }
        if (roas < thresholds.floorRoas() || orders == 0) {
            return "reduce";
        }
        return "optimize";
    }

    static double recommendedBid(double bid, String decision) {
        double factor = switch (decision) {
            case "scale" -> 1.10;
            case "reduce" -> 0.80;
            default -> 1.0;
        };
        return round(bid * factor, 2);
    }

    private LocalDate latestDate() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select max(report_date) as latest_date
                  from wm_ads_lx_ad_report
                 where report_level = 'KEYWORD' and targeting_type = 'manual'
                """, new MapSqlParameterSource());
        LocalDate latest = rows.isEmpty() ? null : date(rows.getFirst().get("latest_date"));
        return latest == null ? UsBusinessDate.usToday() : latest;
    }

    private static String normalizeAdType(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!AD_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported ad type: " + value);
        }
        return normalized;
    }

    private static void validateDates(LocalDate start, LocalDate end) {
        if (start == null || end == null || start.isAfter(end)) {
            throw new IllegalArgumentException("Invalid manual keyword date range");
        }
        if (end.isAfter(LocalDate.now()) || ChronoUnit.DAYS.between(start, end) > 3650) {
            throw new IllegalArgumentException("Manual keyword date range is not supported");
        }
    }

    private static LocalDate date(Object value) {
        if (value instanceof LocalDate localDate) return localDate;
        if (value instanceof Date sqlDate) return sqlDate.toLocalDate();
        return value == null ? null : LocalDate.parse(String.valueOf(value));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long longValue(Object value) {
        if (value == null) return 0;
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static double doubleValue(Object value) {
        if (value == null) return 0;
        return value instanceof Number number ? number.doubleValue() : Double.parseDouble(String.valueOf(value));
    }

    private static double ratio(double numerator, double denominator) {
        return denominator == 0 ? 0 : numerator / denominator;
    }

    private static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private static String parentSku(String campaignName) {
        if (campaignName == null) return null;
        Matcher matcher = PARENT_SKU.matcher(campaignName);
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }

    private static String action(String decision, boolean serving) {
        if (!serving) {
            return "scale".equals(decision) ? "复核后恢复投放" : "保持暂停并复盘";
        }
        return switch (decision) {
            case "scale" -> "竞价提高约 10%";
            case "reduce" -> "竞价降低约 20% 或暂停";
            case "optimize" -> "保持竞价，优化匹配方式";
            default -> "继续积累点击与花费";
        };
    }

    public record Analysis(
            Meta meta,
            Summary summary,
            DecisionThresholds thresholds,
            List<MatchSummary> matchTypes,
            List<DailyTrend> trend,
            List<CampaignOption> campaigns,
            List<KeywordRow> keywords
    ) { }

    public record Meta(
            LocalDate startDate,
            LocalDate endDate,
            LocalDate latestDate,
            String adType,
            int sourceRows,
            int keywordCount
    ) { }

    public record DecisionThresholds(double targetRoas, double floorRoas, int minClicks, double minSpend) { }

    public record Summary(
            int keywordCount,
            int enabledKeywordCount,
            int convertingKeywordCount,
            long impressions,
            long clicks,
            double spend,
            double sales,
            double orders,
            double roas,
            double ctr,
            double cvr,
            double cpc,
            double wasteSpend,
            Map<String, Integer> decisions
    ) {
        private static Summary from(List<KeywordRow> rows) {
            long impressions = rows.stream().mapToLong(KeywordRow::impressions).sum();
            long clicks = rows.stream().mapToLong(KeywordRow::clicks).sum();
            double spend = rows.stream().mapToDouble(KeywordRow::spend).sum();
            double sales = rows.stream().mapToDouble(KeywordRow::sales).sum();
            double orders = rows.stream().mapToDouble(KeywordRow::orders).sum();
            Map<String, Integer> decisions = new LinkedHashMap<>();
            List.of("scale", "reduce", "optimize", "collect").forEach(key -> decisions.put(key, 0));
            rows.forEach(row -> decisions.merge(row.decision(), 1, Integer::sum));
            return new Summary(
                    rows.size(),
                    (int) rows.stream().filter(KeywordRow::serving).count(),
                    (int) rows.stream().filter(row -> row.orders() > 0).count(),
                    impressions, clicks, round(spend, 2), round(sales, 2), round(orders, 2),
                    round(ratio(sales, spend), 6), round(ratio(clicks, impressions), 6),
                    round(ratio(orders, clicks), 6), round(ratio(spend, clicks), 6),
                    round(rows.stream().filter(row -> "reduce".equals(row.decision()))
                            .mapToDouble(KeywordRow::spend).sum(), 2),
                    decisions
            );
        }
    }

    public record MatchSummary(
            String matchType,
            int keywordCount,
            long impressions,
            long clicks,
            double spend,
            double sales,
            double orders,
            double roas,
            double ctr,
            double cvr
    ) {
        private static MatchSummary from(String type, List<KeywordRow> all) {
            List<KeywordRow> rows = all.stream().filter(row -> type.equalsIgnoreCase(row.matchType())).toList();
            long impressions = rows.stream().mapToLong(KeywordRow::impressions).sum();
            long clicks = rows.stream().mapToLong(KeywordRow::clicks).sum();
            double spend = rows.stream().mapToDouble(KeywordRow::spend).sum();
            double sales = rows.stream().mapToDouble(KeywordRow::sales).sum();
            double orders = rows.stream().mapToDouble(KeywordRow::orders).sum();
            return new MatchSummary(type, rows.size(), impressions, clicks,
                    round(spend, 2), round(sales, 2), round(orders, 2),
                    round(ratio(sales, spend), 6), round(ratio(clicks, impressions), 6),
                    round(ratio(orders, clicks), 6));
        }
    }

    public record DailyTrend(
            LocalDate date,
            long impressions,
            long clicks,
            double spend,
            double sales,
            double orders,
            double roas
    ) { }

    public record CampaignOption(String adType, String campaignId, String campaignName, String parentSku) { }

    public record KeywordRow(
            long advertiserId,
            String adType,
            String parentSku,
            String campaignId,
            String campaignName,
            String campaignStatus,
            String adGroupId,
            String adGroupName,
            String adGroupStatus,
            String keywordId,
            String keywordText,
            String keywordState,
            String keywordStatus,
            String matchType,
            double bid,
            double suggestedBid,
            double recommendedBid,
            boolean serving,
            LocalDate latestDate,
            int activeDays,
            long impressions,
            long clicks,
            double spend,
            double sales,
            double orders,
            double units,
            double ctr,
            double cvr,
            double cpc,
            double roas,
            double acos,
            String decision,
            String action
    ) { }

    private static final class MutableKeyword {
        private final Set<LocalDate> dates = new LinkedHashSet<>();
        private LocalDate latestDate;
        private long advertiserId;
        private String adType;
        private String campaignId;
        private String campaignName;
        private String campaignStatus;
        private String adGroupId;
        private String adGroupName;
        private String adGroupStatus;
        private String keywordId;
        private String keywordText;
        private String keywordState;
        private String keywordStatus;
        private String matchType;
        private double bid;
        private double suggestedBid;
        private final MutableMetrics metrics = new MutableMetrics();

        private void add(Map<String, Object> row, LocalDate reportDate) {
            dates.add(reportDate);
            metrics.add(row);
            if (latestDate == null || !reportDate.isBefore(latestDate)) {
                latestDate = reportDate;
                advertiserId = longValue(row.get("advertiser_id"));
                adType = text(row.get("ad_type"));
                campaignId = text(row.get("campaign_id"));
                campaignName = text(row.get("campaign_name"));
                campaignStatus = text(row.get("campaign_status"));
                adGroupId = text(row.get("ad_group_id"));
                adGroupName = text(row.get("ad_group_name"));
                adGroupStatus = text(row.get("ad_group_status"));
                keywordId = text(row.get("keyword_id"));
                keywordText = text(row.get("keyword_text"));
                keywordState = text(row.get("keyword_state"));
                keywordStatus = text(row.get("keyword_status"));
                matchType = text(row.get("match_type"));
                bid = doubleValue(row.get("bid"));
                suggestedBid = doubleValue(row.get("suggested_bid"));
            }
        }

        private KeywordRow toRow(DecisionThresholds thresholds) {
            String decision = decide(metrics.spend, metrics.sales, metrics.orders, metrics.clicks, thresholds);
            boolean serving = "enabled".equalsIgnoreCase(keywordState)
                    && "approved".equalsIgnoreCase(keywordStatus)
                    && (adGroupStatus == null || "enabled".equalsIgnoreCase(adGroupStatus));
            return new KeywordRow(
                    advertiserId, adType, parentSku(campaignName), campaignId, campaignName, campaignStatus,
                    adGroupId, adGroupName, adGroupStatus, keywordId, keywordText, keywordState,
                    keywordStatus, matchType, round(bid, 2), round(suggestedBid, 2),
                    recommendedBid(bid, decision), serving, latestDate, dates.size(),
                    metrics.impressions, metrics.clicks, round(metrics.spend, 2), round(metrics.sales, 2),
                    round(metrics.orders, 2), round(metrics.units, 2),
                    round(ratio(metrics.clicks, metrics.impressions), 6),
                    round(ratio(metrics.orders, metrics.clicks), 6),
                    round(ratio(metrics.spend, metrics.clicks), 6),
                    round(ratio(metrics.sales, metrics.spend), 6),
                    round(ratio(metrics.spend, metrics.sales), 6),
                    decision, action(decision, serving)
            );
        }
    }

    private static final class MutableMetrics {
        private long impressions;
        private long clicks;
        private double spend;
        private double sales;
        private double orders;
        private double units;

        private void add(Map<String, Object> row) {
            impressions += longValue(row.get("impressions"));
            clicks += longValue(row.get("clicks"));
            spend += doubleValue(row.get("ad_spend"));
            sales += doubleValue(row.get("attributed_sales"));
            orders += doubleValue(row.get("attributed_orders"));
            units += doubleValue(row.get("attributed_units"));
        }

        private DailyTrend toTrend(LocalDate date) {
            return new DailyTrend(date, impressions, clicks, round(spend, 2), round(sales, 2),
                    round(orders, 2), round(ratio(sales, spend), 6));
        }
    }
}
