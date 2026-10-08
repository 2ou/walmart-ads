package com.ai.walmartads.walmartcomment;

import com.ai.walmartads.importing.domain.ProductRecord;
import com.ai.walmartads.importing.repository.ProductRecordRepository;
import com.ai.walmartads.walmartcomment.domain.WalmartComment;
import com.ai.walmartads.walmartcomment.domain.WalmartCommentSyncLog;
import com.ai.walmartads.walmartcomment.repository.WalmartCommentRepository;
import com.ai.walmartads.walmartcomment.repository.WalmartCommentSyncLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Walmart 评价口碑查询。
 *
 * <p>本服务明确区分两套不能混用的口径：
 * <ul>
 *   <li>平台累计口径：在线商品接口的 review_count / average_rating，适合展示官方水位；</li>
 *   <li>可分析样本：评价接口能逐条读取、跨变体去重后的正文，适合星级分布、主题与趋势。</li>
 * </ul>
 * 页面所有比率都必须同时返回自己的分子与分母，避免“显示 197 条、却按 69 条算差评率”。
 */
@Service
@Transactional(readOnly = true)
public class WalmartCommentQueryService {

    private static final Logger log = LoggerFactory.getLogger(WalmartCommentQueryService.class);
    private static final String PLATFORM = "walmart";
    private static final int LOW_STAR_CEILING = 3;
    private static final int CRITICAL_STAR_CEILING = 2;
    private static final int SAMPLE_LIMIT = 3;
    private static final int RECENT_LOW_LIMIT = 120;
    private static final Pattern INCENTIVE_PATTERN = Pattern.compile(
            "\\b(received\\s+(it\\s+)?free|received\\s+a\\s+free|free\\s+for\\s+(my\\s+)?honest\\s+review|free\\s+product|in\\s+exchange\\s+for\\s+(an?\\s+)?review|incentivized)\\b",
            Pattern.CASE_INSENSITIVE);

    private final WalmartCommentRepository commentRepository;
    private final WalmartCommentSyncLogRepository logRepository;
    private final ProductRecordRepository productRecordRepository;

    public WalmartCommentQueryService(
            WalmartCommentRepository commentRepository,
            WalmartCommentSyncLogRepository logRepository,
            ProductRecordRepository productRecordRepository
    ) {
        this.commentRepository = commentRepository;
        this.logRepository = logRepository;
        this.productRecordRepository = productRecordRepository;
    }

    /** 口碑页一次性取回轻量聚合；单款全部原文仍由独立下钻接口加载。 */
    public Map<String, Object> buildData() {
        List<WalmartComment> raw = commentRepository.findByPlatformOrderByCommentDateDesc(PLATFORM);
        DedupeResult deduped = dedupeAcrossVariants(raw);
        List<WalmartComment> rows = deduped.rows();
        Map<String, ReportedMetric> reported = loadReportedMetrics();
        BigDecimal globalLowRate = rate(countByStarCeiling(rows, LOW_STAR_CEILING), rows.size());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sync", buildSync());
        data.put("dataQuality", buildDataQuality(raw, deduped, reported));
        data.put("overview", buildOverview(rows, reported));
        data.put("styles", buildStyles(rows, reported, globalLowRate));
        data.put("monthlyTrend", buildMonthlyTrend(rows));
        List<Map<String, Object>> issues = buildThemes(rows, ISSUE_PATTERNS, true);
        data.put("issueThemes", issues);
        data.put("painPoints", issues); // 兼容旧前端字段。
        data.put("positiveThemes", buildThemes(rows, POSITIVE_PATTERNS, false));
        data.put("recentNegatives", buildLowRatings(rows, RECENT_LOW_LIMIT));
        data.put("lowRatingReviews", buildLowRatings(rows, 0)); // 全部 ≤3 星，按 commentDate 降序，供角标计数与弹窗共用
        log.debug("口碑数据构建完成：原始 {} 条，独立样本 {} 条", raw.size(), rows.size());
        return data;
    }

    /** 同一款多个商品行只保留评论数最大的官方水位，并绑定该行的平均星级。 */
    private Map<String, ReportedMetric> loadReportedMetrics() {
        Map<String, ReportedMetric> map = new HashMap<>();
        for (ProductRecord record : productRecordRepository.findAll()) {
            if (!record.isCurrentVersion() || record.getParentSku() == null || record.getParentSku().isBlank()) {
                continue;
            }
            String key = record.getParentSku().trim().toUpperCase(Locale.ROOT);
            int count = record.getReviewCount() == null ? 0 : Math.max(0, record.getReviewCount());
            ReportedMetric candidate = new ReportedMetric(count, record.getAverageRating());
            ReportedMetric current = map.get(key);
            if (current == null || candidate.reviewCount() > current.reviewCount()
                    || (candidate.reviewCount() == current.reviewCount()
                    && current.averageRating() == null && candidate.averageRating() != null)) {
                map.put(key, candidate);
            }
        }
        return map;
    }

    private Map<String, Object> buildOverview(
            List<WalmartComment> rows, Map<String, ReportedMetric> reported) {
        Map<String, Object> overview = new LinkedHashMap<>();
        int matched = 0;
        int low = 0;
        int critical = 0;
        int mixed = 0;
        int incentivized = 0;
        long starSum = 0;
        Map<String, Integer> starDist = new TreeMap<>(Comparator.reverseOrder());
        for (WalmartComment comment : rows) {
            int star = comment.getCommentStar();
            starDist.merge(String.valueOf(star), 1, Integer::sum);
            starSum += star;
            if (comment.hasStyle()) matched++;
            if (star <= LOW_STAR_CEILING) low++;
            if (star <= CRITICAL_STAR_CEILING) critical++;
            if (star == 3) mixed++;
            if (isIncentivized(comment)) incentivized++;
        }

        int platformCount = reported.values().stream().mapToInt(ReportedMetric::reviewCount).sum();
        BigDecimal platformRating = weightedPlatformRating(reported.values());
        long ratedStyles = reported.values().stream().filter(value -> value.reviewCount() > 0).count();
        overview.put("platformReviewCount", platformCount);
        overview.put("reportedReviewCount", platformCount); // 兼容旧前端。
        overview.put("platformAverageRating", platformRating);
        overview.put("totalCount", rows.size());
        overview.put("sampleCount", rows.size());
        overview.put("matchedCount", matched);
        overview.put("unmatchedCount", rows.size() - matched);
        overview.put("avgStar", avg(starSum, rows.size()));
        overview.put("sampleAverageRating", avg(starSum, rows.size()));
        overview.put("starDistribution", starDist);
        overview.put("negativeCount", low);
        overview.put("lowRatingCount", low);
        overview.put("negativeRate", rate(low, rows.size()));
        overview.put("lowRatingRate", rate(low, rows.size()));
        overview.put("badCount", critical);
        overview.put("criticalCount", critical);
        overview.put("mixedCount", mixed);
        overview.put("incentivizedCount", incentivized);
        overview.put("sampleCoverageRate", rate(rows.size(), platformCount));
        overview.put("ratedStyleCount", (int) ratedStyles);
        overview.put("totalStyleCount", reported.size());
        overview.put("windows", buildWindows(rows));
        return overview;
    }

    private Map<String, Object> buildDataQuality(
            List<WalmartComment> raw,
            DedupeResult deduped,
            Map<String, ReportedMetric> reported
    ) {
        int matched = (int) raw.stream().filter(WalmartComment::hasStyle).count();
        int platformCount = reported.values().stream().mapToInt(ReportedMetric::reviewCount).sum();
        Map<String, Object> quality = new LinkedHashMap<>();
        quality.put("rawRowCount", raw.size());
        quality.put("uniqueReviewCount", deduped.rows().size());
        quality.put("collapsedCount", deduped.collapsedCount());
        quality.put("anonymousCollapsedCount", deduped.anonymousCollapsedCount());
        quality.put("matchedCount", matched);
        quality.put("unmatchedCount", raw.size() - matched);
        quality.put("mappingRate", rate(matched, raw.size()));
        quality.put("platformReviewCount", platformCount);
        quality.put("analyzableCoverageRate", rate(deduped.rows().size(), platformCount));

        Optional<WalmartCommentSyncLog> snapshot = logRepository
                .findTopByStatusAndTriggeredByOrderByStartedAtDesc("SUCCESS", "RECONCILE");
        if (snapshot.isEmpty()) {
            snapshot = logRepository.findTopByStatusAndTriggeredByOrderByStartedAtDesc("SUCCESS", "BACKFILL");
        }
        snapshot.ifPresent(log -> {
            quality.put("lastFullSnapshotCount", log.getFetchedCount());
            quality.put("lastFullSnapshotAt", log.getFinishedAt());
            quality.put("sourceLocalDelta", log.getFetchedCount() - raw.size());
        });
        return quality;
    }

    /** 30/90/180/365 个自然日（包含今天）的可分析样本窗口。 */
    private Map<String, Object> buildWindows(List<WalmartComment> rows) {
        Map<String, Object> windows = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        for (int days : new int[]{30, 90, 180, 365}) {
            LocalDate floor = today.minusDays(days - 1L);
            Map<String, Object> summary = summarize(rows, floor, today);
            summary.put("days", days);
            windows.put(String.valueOf(days), summary);
        }
        return windows;
    }

    private Map<String, Object> summarize(List<WalmartComment> rows, LocalDate start, LocalDate end) {
        int count = 0;
        int low = 0;
        int critical = 0;
        long starSum = 0;
        for (WalmartComment comment : rows) {
            if (comment.getCommentDate().isBefore(start) || comment.getCommentDate().isAfter(end)) continue;
            count++;
            starSum += comment.getCommentStar();
            if (comment.getCommentStar() <= LOW_STAR_CEILING) low++;
            if (comment.getCommentStar() <= CRITICAL_STAR_CEILING) critical++;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("count", count);
        summary.put("avgStar", avg(starSum, count));
        summary.put("negativeCount", low);
        summary.put("lowRatingCount", low);
        summary.put("criticalCount", critical);
        summary.put("negativeRate", rate(low, count));
        summary.put("lowRatingRate", rate(low, count));
        return summary;
    }

    private List<Map<String, Object>> buildStyles(
            List<WalmartComment> rows,
            Map<String, ReportedMetric> reported,
            BigDecimal globalLowRate
    ) {
        Map<String, List<WalmartComment>> byStyle = new HashMap<>();
        for (WalmartComment comment : rows) {
            if (comment.hasStyle()) {
                byStyle.computeIfAbsent(comment.getParentSku().toUpperCase(Locale.ROOT), key -> new ArrayList<>())
                        .add(comment);
            }
        }
        Set<String> allStyles = new TreeSet<>();
        allStyles.addAll(reported.keySet());
        allStyles.addAll(byStyle.keySet());
        List<Map<String, Object>> out = new ArrayList<>();
        for (String parentSku : allStyles) {
            out.add(styleView(parentSku, byStyle.getOrDefault(parentSku, List.of()), reported, globalLowRate));
        }
        out.sort(Comparator
                .comparingInt((Map<String, Object> map) -> (int) map.get("riskScore")).reversed()
                .thenComparing(Comparator.comparingInt(
                        (Map<String, Object> map) -> (int) map.get("platformReviewCount")).reversed()));
        return out;
    }

    private Map<String, Object> styleView(
            String parentSku,
            List<WalmartComment> rows,
            Map<String, ReportedMetric> reported,
            BigDecimal globalLowRate
    ) {
        StyleAcc acc = new StyleAcc();
        for (WalmartComment comment : rows) acc.add(comment);
        ReportedMetric official = reported.getOrDefault(parentSku.toUpperCase(Locale.ROOT), ReportedMetric.EMPTY);
        BigDecimal lowRate = rate(acc.low, acc.count);
        LocalDate today = LocalDate.now();
        Map<String, Object> recent30 = summarize(rows, today.minusDays(29), today);
        Map<String, Object> previous30 = summarize(rows, today.minusDays(59), today.minusDays(30));
        BigDecimal recentRate = (BigDecimal) recent30.get("lowRatingRate");
        BigDecimal previousRate = (BigDecimal) previous30.get("lowRatingRate");
        int recentCount = (int) recent30.get("count");
        int previousCount = (int) previous30.get("count");
        BigDecimal trendDelta = recentCount > 0 && previousCount > 0
                ? recentRate.subtract(previousRate).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        String trend = recentCount < 3 || previousCount < 3
                ? "INSUFFICIENT"
                : trendDelta.compareTo(BigDecimal.valueOf(5)) >= 0
                ? "WORSENING"
                : trendDelta.compareTo(BigDecimal.valueOf(-5)) <= 0 ? "IMPROVING" : "STABLE";
        String confidence = sampleConfidence(acc.count);
        String riskLevel = riskLevel(official, acc.count, lowRate, trend);
        int riskScore = riskScore(official, acc.count, lowRate, trend, globalLowRate);

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("parentSku", parentSku);
        view.put("platformReviewCount", official.reviewCount());
        view.put("reportedReviewCount", official.reviewCount());
        view.put("platformAverageRating", official.averageRating());
        view.put("displayAverageRating", official.averageRating() == null ? acc.avg() : official.averageRating());
        view.put("count", acc.count);
        view.put("sampleCount", acc.count);
        view.put("avgStar", acc.avg());
        view.put("sampleAverageRating", acc.avg());
        view.put("fiveStar", acc.star[5]);
        view.put("fourStar", acc.star[4]);
        view.put("threeStar", acc.star[3]);
        view.put("twoStar", acc.star[2]);
        view.put("oneStar", acc.star[1]);
        view.put("negativeCount", acc.low);
        view.put("lowRatingCount", acc.low);
        view.put("criticalCount", acc.critical);
        view.put("mixedCount", acc.star[3]);
        view.put("negativeRate", lowRate);
        view.put("lowRatingRate", lowRate);
        view.put("sampleCoverageRate", rate(acc.count, official.reviewCount()));
        view.put("sampleConfidence", confidence);
        view.put("riskLevel", riskLevel);
        view.put("riskScore", riskScore);
        view.put("trend", trend);
        view.put("recent30Count", recentCount);
        view.put("recent30LowRatingRate", recentRate);
        view.put("previous30LowRatingRate", previousRate);
        view.put("lowRatingRateDelta", trendDelta);
        view.put("latestCommentDate", acc.latest == null ? null : acc.latest.toString());
        return view;
    }

    private static String sampleConfidence(int count) {
        if (count >= 30) return "HIGH";
        if (count >= 10) return "MEDIUM";
        if (count > 0) return "LOW";
        return "NONE";
    }

    private static String riskLevel(
            ReportedMetric official, int sampleCount, BigDecimal lowRate, String trend) {
        if (official.reviewCount() <= 0 && sampleCount <= 0) return "NONE";
        boolean officialRisk = official.averageRating() != null
                && official.averageRating().compareTo(BigDecimal.valueOf(4.2)) < 0;
        boolean officialWatch = official.averageRating() != null
                && official.averageRating().compareTo(BigDecimal.valueOf(4.5)) < 0;
        boolean sampleRisk = sampleCount >= 5 && lowRate.compareTo(BigDecimal.valueOf(15)) >= 0;
        boolean sampleWatch = sampleCount >= 5 && lowRate.compareTo(BigDecimal.valueOf(8)) >= 0;
        if (officialRisk || sampleRisk) return "RISK";
        if (officialWatch || sampleWatch || "WORSENING".equals(trend)) return "WATCH";
        if (sampleCount == 0) return "INSUFFICIENT";
        return "HEALTHY";
    }

    private static int riskScore(
            ReportedMetric official,
            int sampleCount,
            BigDecimal lowRate,
            String trend,
            BigDecimal globalLowRate
    ) {
        double effectiveLowRate = sampleCount > 0 ? lowRate.doubleValue() : globalLowRate.doubleValue();
        double score = Math.min(55, effectiveLowRate * 2.2);
        if (official.averageRating() != null) {
            score += Math.max(0, Math.min(30, (4.6 - official.averageRating().doubleValue()) * 30));
        }
        if ("WORSENING".equals(trend)) score += 12;
        if (sampleCount > 0 && sampleCount < 5) score *= 0.75;
        return (int) Math.max(0, Math.min(100, Math.round(score)));
    }

    private List<Map<String, Object>> buildMonthlyTrend(List<WalmartComment> rows) {
        Map<String, long[]> byMonth = new TreeMap<>();
        for (WalmartComment comment : rows) {
            String month = comment.getCommentDate().format(DateTimeFormatter.ofPattern("yyyy-MM"));
            long[] cell = byMonth.computeIfAbsent(month, key -> new long[]{0, 0, 0, 0});
            cell[0]++;
            cell[1] += comment.getCommentStar();
            if (comment.getCommentStar() <= LOW_STAR_CEILING) cell[2]++;
            if (comment.getCommentStar() <= CRITICAL_STAR_CEILING) cell[3]++;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : byMonth.entrySet()) {
            long[] cell = entry.getValue();
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("month", entry.getKey());
            point.put("count", (int) cell[0]);
            point.put("avgStar", avg(cell[1], cell[0]));
            point.put("negativeCount", (int) cell[2]);
            point.put("lowRatingCount", (int) cell[2]);
            point.put("criticalCount", (int) cell[3]);
            point.put("lowRatingRate", rate((int) cell[2], (int) cell[0]));
            out.add(point);
        }
        return out;
    }

    private List<Map<String, Object>> buildThemes(
            List<WalmartComment> rows, List<ThemePattern> patterns, boolean lowOnly) {
        Map<String, ThemeAcc> byTheme = new LinkedHashMap<>();
        for (ThemePattern pattern : patterns) byTheme.put(pattern.label(), new ThemeAcc(pattern));
        for (WalmartComment comment : rows) {
            if (lowOnly && comment.getCommentStar() > LOW_STAR_CEILING) continue;
            if (!lowOnly && comment.getCommentStar() < 4) continue;
            String text = searchableText(comment);
            if (text.isBlank()) continue;
            for (ThemePattern pattern : patterns) {
                if (pattern.matcher().matcher(text).find()) byTheme.get(pattern.label()).hit(comment);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ThemeAcc acc : byTheme.values()) {
            if (acc.hits == 0) continue;
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("label", acc.pattern.label());
            view.put("group", acc.pattern.group());
            view.put("action", acc.pattern.action());
            view.put("hits", acc.hits);
            view.put("styleCount", acc.styles.size());
            view.put("crossStyle", acc.styles.size() >= 2);
            view.put("styles", acc.styles.stream().sorted().toList());
            view.put("samples", acc.samples);
            out.add(view);
        }
        out.sort(Comparator
                .comparingInt((Map<String, Object> map) -> (int) map.get("styleCount")).reversed()
                .thenComparing(Comparator.comparingInt(
                        (Map<String, Object> map) -> (int) map.get("hits")).reversed()));
        return out;
    }

    private List<Map<String, Object>> buildLowRatings(List<WalmartComment> rows, int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (WalmartComment comment : rows) {
            if (comment.getCommentStar() > LOW_STAR_CEILING) continue;
            out.add(commentView(comment));
            if (limit > 0 && out.size() >= limit) break;
        }
        return out;
    }

    public Map<String, Object> buildStyleDetail(String parentSku) {
        if (parentSku == null || parentSku.isBlank()) throw new IllegalArgumentException("款号不能为空");
        String key = parentSku.trim().toUpperCase(Locale.ROOT);
        List<WalmartComment> raw = commentRepository.findByPlatformOrderByCommentDateDesc(PLATFORM).stream()
                .filter(comment -> comment.hasStyle() && key.equalsIgnoreCase(comment.getParentSku()))
                .toList();
        DedupeResult deduped = dedupeAcrossVariants(raw);
        List<WalmartComment> rows = deduped.rows();
        Map<String, ReportedMetric> reported = loadReportedMetrics();
        BigDecimal globalRate = rate(countByStarCeiling(rows, LOW_STAR_CEILING), rows.size());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("parentSku", key);
        data.put("summary", styleView(key, rows, reported, globalRate));
        data.put("dataQuality", Map.of(
                "rawRowCount", raw.size(),
                "uniqueReviewCount", rows.size(),
                "collapsedCount", deduped.collapsedCount()));
        data.put("starDistribution", starDistribution(rows));
        data.put("windows", buildWindows(rows));
        data.put("monthlyTrend", buildMonthlyTrend(rows));
        data.put("issueThemes", buildThemes(rows, ISSUE_PATTERNS, true));
        data.put("painPoints", buildThemes(rows, ISSUE_PATTERNS, true));
        data.put("positiveThemes", buildThemes(rows, POSITIVE_PATTERNS, false));
        data.put("negatives", buildLowRatings(rows, 0));
        return data;
    }

    private Map<String, Integer> starDistribution(List<WalmartComment> rows) {
        Map<String, Integer> dist = new TreeMap<>(Comparator.reverseOrder());
        for (WalmartComment comment : rows) {
            dist.merge(String.valueOf(comment.getCommentStar()), 1, Integer::sum);
        }
        return dist;
    }

    private Map<String, Object> buildSync() {
        Map<String, Object> sync = new LinkedHashMap<>();
        commentRepository.findMaxCommentDateByPlatform(PLATFORM)
                .ifPresent(value -> sync.put("latestCommentDate", value.toString()));
        commentRepository.findTopByPlatformOrderBySyncedAtDesc(PLATFORM)
                .ifPresent(value -> sync.put("lastRowUpdatedAt", value.getSyncedAt()));
        logRepository.findTopByOrderByStartedAtDesc()
                .ifPresent(value -> sync.put("lastLog", syncToView(value)));
        logRepository.findTopByStatusAndTriggeredByOrderByStartedAtDesc("SUCCESS", "RECONCILE")
                .or(() -> logRepository.findTopByStatusAndTriggeredByOrderByStartedAtDesc("SUCCESS", "BACKFILL"))
                .ifPresent(value -> sync.put("lastReconcile", syncToView(value)));
        return sync;
    }

    private Map<String, Object> syncToView(WalmartCommentSyncLog value) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("status", value.getStatus());
        view.put("triggeredBy", value.getTriggeredBy());
        view.put("startedAt", value.getStartedAt());
        view.put("finishedAt", value.getFinishedAt());
        view.put("windowStart", value.getWindowStart().toString());
        view.put("windowEnd", value.getWindowEnd().toString());
        view.put("fetchedCount", value.getFetchedCount());
        view.put("insertedCount", value.getInsertedCount());
        view.put("updatedCount", value.getUpdatedCount());
        view.put("matchedSkuCount", value.getMatchedSkuCount());
        view.put("unmatchedCount", value.getUnmatchedCount());
        view.put("avgStar", value.getAvgStar());
        view.put("errorMessage", value.getErrorMessage());
        return view;
    }

    /**
     * 领星的 comment_id 是 Item 作用域，同一条买家评价会被同款多个 Item 复制返回。
     * 有买家名时使用“店铺+款+日期+星级+买家+正文”；匿名但有正文时，仅当相同指纹横跨多个 Item
     * 才折叠，避免把同一 Item 上两个只写 “Yes” 的真实匿名买家误删。
     */
    private static DedupeResult dedupeAcrossVariants(List<WalmartComment> rows) {
        Map<String, Set<String>> anonymousItems = new HashMap<>();
        for (WalmartComment comment : rows) {
            if (!normalize(comment.getBuyerName()).isEmpty() || !hasText(comment)) continue;
            anonymousItems.computeIfAbsent(anonymousKey(comment), key -> new LinkedHashSet<>())
                    .add(normalize(comment.getPlatformItemId()));
        }
        Map<String, WalmartComment> unique = new LinkedHashMap<>();
        int anonymousCollapsed = 0;
        for (WalmartComment comment : rows) {
            String buyer = normalize(comment.getBuyerName());
            String key;
            if (!buyer.isEmpty() && hasText(comment)) {
                key = "buyer:" + baseKey(comment) + "\u0001" + buyer;
            } else if (buyer.isEmpty() && hasText(comment)
                    && anonymousItems.getOrDefault(anonymousKey(comment), Set.of()).size() > 1) {
                key = "anonymous:" + anonymousKey(comment);
            } else {
                key = "id:" + comment.getCommentId();
            }
            if (unique.putIfAbsent(key, comment) != null && key.startsWith("anonymous:")) {
                anonymousCollapsed++;
            }
        }
        int collapsed = rows.size() - unique.size();
        if (collapsed > 0) {
            log.debug("评价跨变体折叠：{} → {}，其中匿名复制 {} 条", rows.size(), unique.size(), anonymousCollapsed);
        }
        return new DedupeResult(new ArrayList<>(unique.values()), collapsed, anonymousCollapsed);
    }

    private static boolean hasText(WalmartComment comment) {
        return !normalize(comment.getCommentTitle()).isEmpty() || !normalize(comment.getCommentContent()).isEmpty();
    }

    private static String baseKey(WalmartComment comment) {
        return normalize(comment.getStoreId()) + "\u0001" + normalize(comment.getParentSku())
                + "\u0001" + comment.getCommentDate() + "\u0001" + comment.getCommentStar()
                + "\u0001" + normalize(comment.getCommentTitle())
                + "\u0001" + normalize(comment.getCommentContent());
    }

    private static String anonymousKey(WalmartComment comment) {
        return baseKey(comment);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> commentView(WalmartComment comment) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", comment.getId());
        view.put("commentId", comment.getCommentId());
        view.put("parentSku", comment.getParentSku());
        view.put("platformItemId", comment.getPlatformItemId());
        view.put("star", comment.getCommentStar());
        view.put("severity", comment.getCommentStar() <= CRITICAL_STAR_CEILING ? "CRITICAL" : "MIXED");
        view.put("commentDate", comment.getCommentDate().toString());
        view.put("title", comment.getCommentTitle());
        view.put("content", comment.getCommentContent());
        view.put("buyerName", comment.getBuyerName());
        view.put("productTitle", comment.getProductTitle());
        view.put("hasStyle", comment.hasStyle());
        view.put("incentivized", isIncentivized(comment));
        List<ThemePattern> matched = matchingIssues(comment);
        view.put("issueLabels", matched.stream().map(ThemePattern::label).toList());
        view.put("issueGroups", matched.stream().map(ThemePattern::group).distinct().toList());
        return view;
    }

    private static List<ThemePattern> matchingIssues(WalmartComment comment) {
        String text = searchableText(comment);
        if (text.isBlank()) return List.of();
        return ISSUE_PATTERNS.stream().filter(pattern -> pattern.matcher().matcher(text).find()).toList();
    }

    private static boolean isIncentivized(WalmartComment comment) {
        return INCENTIVE_PATTERN.matcher(searchableText(comment)).find();
    }

    private static String searchableText(WalmartComment comment) {
        return ((comment.getCommentTitle() == null ? "" : comment.getCommentTitle()) + " "
                + (comment.getCommentContent() == null ? "" : comment.getCommentContent()))
                .replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static int countByStarCeiling(List<WalmartComment> rows, int ceiling) {
        return (int) rows.stream().filter(comment -> comment.getCommentStar() <= ceiling).count();
    }

    private static BigDecimal avg(long starSum, long count) {
        return count <= 0 ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(starSum).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal rate(int part, int whole) {
        return whole <= 0 ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal weightedPlatformRating(Iterable<ReportedMetric> metrics) {
        BigDecimal weighted = BigDecimal.ZERO;
        long denominator = 0;
        for (ReportedMetric metric : metrics) {
            if (metric.reviewCount() <= 0 || metric.averageRating() == null) continue;
            weighted = weighted.add(metric.averageRating().multiply(BigDecimal.valueOf(metric.reviewCount())));
            denominator += metric.reviewCount();
        }
        return denominator == 0 ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : weighted.divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private record ReportedMetric(int reviewCount, BigDecimal averageRating) {
        private static final ReportedMetric EMPTY = new ReportedMetric(0, null);
    }

    private record DedupeResult(
            List<WalmartComment> rows, int collapsedCount, int anonymousCollapsedCount) {
    }

    private static final class StyleAcc {
        private final int[] star = new int[6];
        private int count;
        private long starSum;
        private int low;
        private int critical;
        private LocalDate latest;

        void add(WalmartComment comment) {
            count++;
            int value = Math.max(1, Math.min(5, comment.getCommentStar()));
            star[value]++;
            starSum += value;
            if (value <= LOW_STAR_CEILING) low++;
            if (value <= CRITICAL_STAR_CEILING) critical++;
            if (latest == null || comment.getCommentDate().isAfter(latest)) latest = comment.getCommentDate();
        }

        BigDecimal avg() {
            return WalmartCommentQueryService.avg(starSum, count);
        }
    }

    private record ThemePattern(String label, String group, String action, Pattern matcher) {
        ThemePattern(String label, String group, String action, String regex) {
            this(label, group, action, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        }
    }

    private static final class ThemeAcc {
        private final ThemePattern pattern;
        private int hits;
        private final Set<String> styles = new LinkedHashSet<>();
        private final List<String> samples = new ArrayList<>();

        ThemeAcc(ThemePattern pattern) {
            this.pattern = pattern;
        }

        void hit(WalmartComment comment) {
            hits++;
            styles.add(comment.hasStyle() ? comment.getParentSku() : "(未归款)");
            if (samples.size() >= SAMPLE_LIMIT) return;
            String text = comment.getCommentContent();
            if (text == null || text.isBlank()) text = comment.getCommentTitle();
            if (text == null || text.isBlank()) return;
            String compact = text.replaceAll("\\s+", " ").trim();
            samples.add(compact.length() > 240 ? compact.substring(0, 240) + "…" : compact);
        }
    }

    private static final List<ThemePattern> ISSUE_PATTERNS = List.of(
            new ThemePattern("面料透 / 太薄", "PRODUCT", "核对面料克重，并在详情页明确透感与内搭建议",
                    "\\b(see[\\s-]?through|see\\s+thru|sheer|too\\s+thin|very\\s+thin)\\b"),
            new ThemePattern("材质或描述不符", "EXPECTATION", "校对标题、材质字段和详情页描述",
                    "\\b(not\\s+(rayon|cotton|silk)|cheap\\s+polyester|material\\s+not\\s+as\\s+described|fake)\\b"),
            new ThemePattern("质量廉价感", "PRODUCT", "复核面料手感、辅料和成衣质检标准",
                    "\\b(cheap(?:\\s+(looking|material|fabric))?|feels?\\s+cheap|inferior|poor\\s+quality|bad\\s+quality|crappy\\s+material)\\b"),
            new ThemePattern("偏小 / 需加大一码", "SIZE", "核对实测尺寸并修正尺码建议",
                    "\\b(too\\s+small|runs?\\s+small|smaller\\s+than|size\\s+up|small\\s+fitting)\\b"),
            new ThemePattern("偏大 / 版型宽松", "SIZE", "核对实测尺寸并补充宽松版型说明",
                    "\\b(too\\s+large|runs?\\s+large|too\\s+big|boxy|size\\s+down|bigger\\s+than\\s+expected)\\b"),
            new ThemePattern("局部偏紧 / 活动受限", "SIZE", "检查袖笼、腰围等局部尺寸与弹力",
                    "\\b(too\\s+tight|very\\s+tight|so\\s+tight|tight\\s+around|uncomfortably\\s+tight|runs?\\s+tight|constricting)\\b"),
            new ThemePattern("过长 / 过短", "SIZE", "按身高补充衣长裤长说明，复核版型尺寸",
                    "\\b(way\\s+too\\s+long|too\\s+long|too\\s+short|very\\s+short|not\\s+long\\s+enough)\\b"),
            new ThemePattern("闷热 / 不透气", "COMFORT", "复核季节定位、透气性与面料说明",
                    "\\b(too\\s+hot|too\\s+warm|much\\s+too\\s+warm|not\\s+breathable|sweaty)\\b"),
            new ThemePattern("缩水 / 褪色", "PRODUCT", "增加洗涤测试并完善护理说明",
                    "\\b(shrink\\w*|shrunk|fad(e|es|ed|ing)|color\\s+bleed\\w*)\\b"),
            new ThemePattern("破损 / 做工问题", "PRODUCT", "抽检走线、接缝、拉链和纽扣牢度",
                    "\\b(loose\\s+thread|thread\\s+came|stitch\\w*\\s+(came|broke|poor)|seam\\s+(split|broke|open)|hole|torn|ripped|defect\\w*|button\\s+(came|fell|missing|loose)|zipper\\s+(broke|stuck|poor))\\b"),
            new ThemePattern("功能细节问题", "PRODUCT", "复核口袋、抽绳和松紧带结构",
                    "\\b(no\\s+pockets?|pocket\\s+(too\\s+small|shallow|torn|missing)|no\\s+drawstring|drawstring\\s+(missing|broke|short)|elastic\\s+(too\\s+tight|too\\s+loose|broke)|waistband\\s+(tight|loose|broke))\\b"),
            new ThemePattern("发错货 / 发错尺码", "FULFILLMENT", "核对仓库拣货标签和变体映射",
                    "\\b(wrong\\s+(size|color|item|product)|sent\\s+the\\s+wrong|received\\s+the\\s+wrong)\\b"),
            new ThemePattern("物流 / 未收到", "FULFILLMENT", "与商品质量分开统计并排查承运与签收异常",
                    "\\b(never\\s+arrived|never\\s+received|didn.?t\\s+receive|not\\s+delivered|delivery\\s+person|delivered\\s+to\\s+wrong|package\\s+(was\\s+)?stolen|shipping\\s+delay)\\b")
    );

    private static final List<ThemePattern> POSITIVE_PATTERNS = List.of(
            new ThemePattern("柔软舒适", "COMFORT", "作为标题、卖点与广告素材的核心表达",
                    "\\b(soft|comfortable|comfy|cozy)\\b"),
            new ThemePattern("合身 / 尺码准确", "FIT", "保留现有尺码建议并强化真实试穿信息",
                    "\\b(perfect\\s+fit|fits?\\s+(well|perfectly)|true\\s+to\\s+size|sizing\\s+is\\s+accurate)\\b"),
            new ThemePattern("外观与颜色好看", "STYLE", "沉淀高频颜色与穿搭场景用于主图和广告",
                    "\\b(cute|beautiful|pretty|gorgeous|flattering|stylish|classy|love\\s+the\\s+color)\\b"),
            new ThemePattern("质量与做工认可", "QUALITY", "作为质量背书，并持续跟踪同款低星反例",
                    "\\b(good\\s+quality|great\\s+quality|nice\\s+quality|well[\\s-]?made|held\\s+up\\s+nicely)\\b"),
            new ThemePattern("性价比认可", "VALUE", "可用于促销页和广告价格利益点",
                    "\\b(great\\s+(price|deal|value)|good\\s+value|affordable|reasonably\\s+priced)\\b"),
            new ThemePattern("轻盈好穿", "COMFORT", "强化季节和日常穿着场景",
                    "\\b(lightweight|light\\s+weight|light\\s+and\\s+airy)\\b"),
            new ThemePattern("保暖", "COMFORT", "用于秋冬季节素材，但避免与闷热差评混用",
                    "\\b(warm\\s+and\\s+cozy|keeps?\\s+me\\s+warm|great\\s+for\\s+(the\\s+)?cold|winter\\s+wear)\\b"),
            new ThemePattern("口袋实用", "FUNCTION", "在主图和五点中明确展示口袋结构",
                    "\\b(love\\s+the\\s+pockets?|pockets?\\s+(are\\s+)?great|even\\s+have\\s+pockets?)\\b")
    );
}
