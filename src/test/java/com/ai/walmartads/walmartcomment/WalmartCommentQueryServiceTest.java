package com.ai.walmartads.walmartcomment;

import com.ai.walmartads.walmartcomment.domain.WalmartComment;
import com.ai.walmartads.walmartcomment.repository.WalmartCommentRepository;
import com.ai.walmartads.walmartcomment.repository.WalmartCommentSyncLogRepository;
import com.ai.walmartads.importing.domain.ProductRecord;
import com.ai.walmartads.importing.repository.ProductRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 口碑聚合口径单测：全表内存聚合的数值正确性（均分 / 差评率 / 窗口 / 款级 / 月度 / 痛点词表）。
 *
 * <p>日期一律相对 {@code LocalDate.now()} 构造，使 30/90/180/365 天窗口的断言在任意运行日都稳定。
 */
@ExtendWith(MockitoExtension.class)
class WalmartCommentQueryServiceTest {

    @Mock
    private WalmartCommentRepository commentRepository;

    @Mock
    private WalmartCommentSyncLogRepository logRepository;

    @Mock
    private ProductRecordRepository productRecordRepository;

    private WalmartCommentQueryService service;
    private List<WalmartComment> rows;

    @BeforeEach
    void setUp() {
        service = new WalmartCommentQueryService(commentRepository, logRepository, productRecordRepository);
        when(productRecordRepository.findAll()).thenReturn(List.of());
        LocalDate today = LocalDate.now();
        // 9 条：7 条已归款、2 条未归款；5 条 ≤3 星。
        rows = List.of(
                comment("c-a1", "PNK001", today.minusDays(20), 5, "Love it", ""),
                comment("c-a2", "PNK001", today.minusDays(19), 5, null, "Perfect fit."),
                comment("c-a3", "PNK001", today.minusDays(18), 2, "Disappointed",
                        "The fabric is too thin and see through"),
                comment("c-b1", "PNK002", today.minusDays(35), 4, null, "Nice color."),
                comment("c-b2", "PNK002", today.minusDays(30), 3, null,
                        "Fits too small, size up. Nothing else."),
                comment("c-c1", null, today.minusDays(8), 1, null, "cheap polyester junk"),
                comment("c-c2", null, today.minusDays(80), 5, null, null),
                comment("c-d1", "PNK003", today.minusDays(2), 3, null,
                        "Button came off and the pocket is missing a drawstring"),
                comment("c-e1", "PNK003", today.minusDays(1), 2, null, "This is sheer and defective")
        );
        // lenient：去重用例会用自己的数据集覆盖这一桩，严格模式下会被判为「多余桩」。
        lenient().when(commentRepository.findByPlatformOrderByCommentDateDesc(eq("walmart"))).thenReturn(rows);
        when(commentRepository.findMaxCommentDateByPlatform(eq("walmart")))
                .thenReturn(Optional.of(today.minusDays(1)));
        when(commentRepository.findTopByPlatformOrderBySyncedAtDesc(eq("walmart")))
                .thenReturn(Optional.of(rows.get(0)));
        when(logRepository.findTopByOrderByStartedAtDesc()).thenReturn(Optional.empty());
    }

    @Test
    void buildDataExposesAllViewSections() {
        Map<String, Object> data = service.buildData();

        assertThat(data).containsKeys(
                "sync", "overview", "styles", "monthlyTrend", "painPoints", "recentNegatives");
        assertThat(data.get("sync")).isInstanceOf(Map.class);
    }

    @Test
    @SuppressWarnings("unchecked")
    void overviewAggregatesMatchedAverageAndNegativeRate() {
        Map<String, Object> overview = (Map<String, Object>) service.buildData().get("overview");

        assertThat((int) overview.get("totalCount")).isEqualTo(9);
        assertThat((int) overview.get("matchedCount")).isEqualTo(7);
        assertThat((int) overview.get("unmatchedCount")).isEqualTo(2);
        assertThat((BigDecimal) overview.get("avgStar")).isEqualByComparingTo(new BigDecimal("3.33"));
        assertThat((int) overview.get("negativeCount")).isEqualTo(5);
        assertThat((BigDecimal) overview.get("negativeRate")).isEqualByComparingTo(new BigDecimal("55.56"));
        assertThat((int) overview.get("badCount")).isEqualTo(3);

        Map<String, Integer> distribution = (Map<String, Integer>) overview.get("starDistribution");
        assertThat(distribution).containsExactlyInAnyOrderEntriesOf(
                Map.of("5", 3, "4", 1, "3", 2, "2", 2, "1", 1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void windowsAreExhaustiveAndMonotonic() {
        Map<String, Object> overview = (Map<String, Object>) service.buildData().get("overview");
        Map<String, Object> windows = (Map<String, Object>) overview.get("windows");

        // 前端以字符串键取窗口（Java 侧 String.valueOf(days)），键缺失会让「近 30 天」卡片静默归零。
        assertThat(windows.keySet()).containsExactly("30", "90", "180", "365");

        int previous = -1;
        for (String key : List.of("30", "90", "180", "365")) {
            Map<String, Object> window = (Map<String, Object>) windows.get(key);
            assertThat((int) window.get("days")).isEqualTo(Integer.parseInt(key));
            assertThat(window).containsKeys("count", "avgStar", "negativeCount", "negativeRate");
            int count = (int) window.get("count");
            assertThat(count).as("%s 天窗口条数不应少于更窄的窗口", key).isGreaterThanOrEqualTo(previous);
            previous = count;
        }

        // “近 30 个自然日”包含今天与此前 29 天；恰好第 30 天的 c-b2 不应多算一天。
        int count30 = (int) ((Map<String, Object>) windows.get("30")).get("count");
        int count90 = (int) ((Map<String, Object>) windows.get("90")).get("count");
        assertThat(count30).isEqualTo(6);
        assertThat(count90).isEqualTo(9);
        assertThat((int) ((Map<String, Object>) windows.get("365")).get("count")).isEqualTo(9);

        // 30 天窗口：6 条里 4 条 ≤3 星。
        assertThat((int) ((Map<String, Object>) windows.get("30")).get("negativeCount")).isEqualTo(4);
        assertThat((BigDecimal) ((Map<String, Object>) windows.get("30")).get("avgStar"))
                .isEqualByComparingTo(new BigDecimal("3.00"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void stylesRankByVolumeAndExcludeUnmatched() {
        List<Map<String, Object>> styles = (List<Map<String, Object>>) service.buildData().get("styles");

        assertThat(styles).extracting(map -> map.get("parentSku"))
                .containsExactlyInAnyOrder("PNK001", "PNK002", "PNK003");
        Map<String, Map<String, Object>> bySku = new java.util.HashMap<>();
        styles.forEach(row -> bySku.put((String) row.get("parentSku"), row));

        Map<String, Object> pnk001 = bySku.get("PNK001");
        assertThat((int) pnk001.get("count")).isEqualTo(3);
        assertThat((BigDecimal) pnk001.get("avgStar")).isEqualByComparingTo(new BigDecimal("4.00"));
        assertThat((int) pnk001.get("fiveStar")).isEqualTo(2);
        assertThat((int) pnk001.get("twoStar")).isEqualTo(1);
        assertThat((int) pnk001.get("negativeCount")).isEqualTo(1);
        assertThat((BigDecimal) pnk001.get("negativeRate")).isEqualByComparingTo(new BigDecimal("33.33"));
        assertThat(pnk001.get("latestCommentDate")).isNotNull();

        Map<String, Object> pnk003 = bySku.get("PNK003");
        assertThat((BigDecimal) pnk003.get("negativeRate")).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    @SuppressWarnings("unchecked")
    void monthlyTrendIsAscendingAndComplete() {
        List<Map<String, Object>> trend = (List<Map<String, Object>>) service.buildData().get("monthlyTrend");

        assertThat(trend).isNotEmpty();
        int total = 0;
        int negative = 0;
        String previousMonth = null;
        for (Map<String, Object> point : trend) {
            String month = (String) point.get("month");
            assertThat(month).isNotBlank();
            if (previousMonth != null) {
                assertThat(month).as("月份必须升序").isGreaterThan(previousMonth);
            }
            previousMonth = month;
            total += (int) point.get("count");
            negative += (int) point.get("negativeCount");
        }
        assertThat(total).isEqualTo(9);
        assertThat(negative).isEqualTo(5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void painPatternsUseWordBoundariesAndAggregateAcrossStyles() {
        List<Map<String, Object>> pains = (List<Map<String, Object>>) service.buildData().get("painPoints");
        Map<String, Map<String, Object>> byLabel = new java.util.LinkedHashMap<>();
        for (Map<String, Object> pain : pains) {
            byLabel.put((String) pain.get("label"), pain);
        }

        // 「面料透 / 太薄」跨 2 个款命中 → styleCount ≥ 2 才是共性问题，可提给工厂。
        Map<String, Object> thin = byLabel.get("面料透 / 太薄");
        assertThat(thin).isNotNull();
        assertThat((int) thin.get("hits")).isEqualTo(2);
        assertThat((int) thin.get("styleCount")).isEqualTo(2);
        assertThat((List<String>) thin.get("styles")).containsExactly("PNK001", "PNK003");

        // 词边界：c-b2 的 "Nothing" 含子串 "thin"，但 \b 边界应挡住误报。
        Map<String, Object> small = byLabel.get("偏小 / 需加大一码");
        assertThat(small).isNotNull();
        assertThat((int) small.get("hits")).isEqualTo(1);
        assertThat((int) small.get("styleCount")).isEqualTo(1);
        assertThat((List<String>) small.get("styles")).containsExactly("PNK002");

        // 使用明确问题短语，不能因正文只出现 pocket / elastic 等名词就误判。
        assertThat((int) byLabel.get("破损 / 做工问题").get("hits")).isEqualTo(2);
        assertThat(byLabel).doesNotContainKey("功能细节问题");

        // 未归款评价的命中归入「(未归款)」桶，不混入款号统计。
        assertThat((List<String>) byLabel.get("质量廉价感").get("styles")).containsExactly("(未归款)");
        assertThat((List<String>) byLabel.get("材质或描述不符").get("styles")).containsExactly("(未归款)");

        // 只扫描 ≤3 星：5 星的 "Perfect fit." 不得进入任何痛点样本。
        assertThat(byLabel.values())
                .extracting(map -> ((List<String>) map.get("samples")).toString())
                .noneMatch(sample -> sample.contains("Perfect fit"));

        // 样本上限 3 条，避免响应体膨胀。
        assertThat(byLabel.values())
                .allSatisfy(pain -> assertThat((List<String>) pain.get("samples")).hasSizeLessThanOrEqualTo(3));
    }

    @Test
    @SuppressWarnings("unchecked")
    void recentNegativesOnlyContainsLowStarRows() {
        List<Map<String, Object>> negatives = (List<Map<String, Object>>) service.buildData().get("recentNegatives");

        assertThat(negatives).hasSize(5);
        assertThat(negatives).allSatisfy(row ->
                assertThat((int) row.get("star")).as("差评明细只收 ≤3 星").isLessThanOrEqualTo(3));
        // 明细行必须带款号列（未归款为 null，前端显示「未归款」）与日期字符串。
        assertThat(negatives)
                .allSatisfy(row -> {
                    assertThat(row.get("commentDate")).isInstanceOf(String.class);
                    assertThat(row).containsKey("parentSku").containsKey("platformItemId");
                });
    }

    /**
     * 同款各变体 item 会把同一条评价各回带一份（comment_id 是 item 作用域），必须折叠成一条，
     * 否则款级条数 / 星级分布 / 痛点命中会被变体数放大；但「只打星不留字」的评价不得折叠。
     */
    @Test
    @SuppressWarnings("unchecked")
    void variantDuplicatedCommentsCollapseToOneEvaluation() {
        LocalDate today = LocalDate.now();
        List<WalmartComment> duplicated = List.of(
                commentWithBuyer("dup-1", "PNK010", today.minusDays(3), 2, "Too thin", "Fabric is see through", "Victoria"),
                commentWithBuyer("dup-2", "PNK010", today.minusDays(3), 2, "Too thin", "Fabric is see through", "Victoria"),
                commentWithBuyer("dup-3", "PNK010", today.minusDays(3), 2, "Too thin", "Fabric is see through", " Victoria "),
                // 无正文、无买家名：不可判定为同一条，按 commentId 原样保留。
                commentWithBuyer("star-1", "PNK010", today.minusDays(2), 5, null, null, ""),
                commentWithBuyer("star-2", "PNK010", today.minusDays(2), 5, null, null, ""));
        when(commentRepository.findByPlatformOrderByCommentDateDesc(eq("walmart"))).thenReturn(duplicated);

        Map<String, Object> data = service.buildData();
        List<Map<String, Object>> styles = (List<Map<String, Object>>) data.get("styles");

        assertThat(styles).hasSize(1);
        Map<String, Object> stat = styles.get(0);
        assertThat((int) stat.get("count")).as("3 条变体复制折叠为 1 条，2 条无正文评价保留").isEqualTo(3);
        assertThat((int) stat.get("negativeCount")).isEqualTo(1);
        assertThat((BigDecimal) stat.get("negativeRate")).isEqualByComparingTo(new BigDecimal("33.33"));
        assertThat((int) ((Map<String, Object>) data.get("overview")).get("totalCount")).isEqualTo(3);
        assertThat(((List<Map<String, Object>>) data.get("recentNegatives"))).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void anonymousTextCopiedAcrossItemsIsCollapsedButRatingOnlyRowsArePreserved() {
        LocalDate today = LocalDate.now();
        List<WalmartComment> duplicated = List.of(
                commentWithBuyer("anon-1", "PNK010", today.minusDays(3), 5, "Classy", "Soft and comfy", ""),
                commentWithBuyer("anon-2", "PNK010", today.minusDays(3), 5, "Classy", "Soft and comfy", ""),
                commentWithBuyer("star-1", "PNK010", today.minusDays(2), 5, null, null, ""),
                commentWithBuyer("star-2", "PNK010", today.minusDays(2), 5, null, null, ""));
        when(commentRepository.findByPlatformOrderByCommentDateDesc(eq("walmart"))).thenReturn(duplicated);

        Map<String, Object> data = service.buildData();
        Map<String, Object> overview = (Map<String, Object>) data.get("overview");
        Map<String, Object> quality = (Map<String, Object>) data.get("dataQuality");

        assertThat((int) overview.get("sampleCount")).isEqualTo(3);
        assertThat((int) quality.get("collapsedCount")).isEqualTo(1);
        assertThat((int) quality.get("anonymousCollapsedCount")).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void reportedOnlyStyleIsNotShownAsNoReview() {
        ProductRecord record = mock(ProductRecord.class);
        when(record.isCurrentVersion()).thenReturn(true);
        when(record.getParentSku()).thenReturn("PNK099");
        when(record.getReviewCount()).thenReturn(4);
        when(record.getAverageRating()).thenReturn(new BigDecimal("4.50"));
        when(productRecordRepository.findAll()).thenReturn(List.of(record));

        List<Map<String, Object>> styles = (List<Map<String, Object>>) service.buildData().get("styles");
        Map<String, Object> pnk099 = styles.stream()
                .filter(row -> "PNK099".equals(row.get("parentSku")))
                .findFirst().orElseThrow();

        assertThat((int) pnk099.get("platformReviewCount")).isEqualTo(4);
        assertThat((int) pnk099.get("sampleCount")).isZero();
        assertThat(pnk099.get("riskLevel")).isEqualTo("INSUFFICIENT");
    }

    private WalmartComment comment(String id, String parentSku, LocalDate date, int star,
                                   String title, String content) {
        return new WalmartComment("walmart", id, "110358320595211264", "PINKSIR",
                "item-" + id, parentSku, date, star, title, content, "buyer-" + id,
                "product title", null, null, "US", null, LocalDateTime.now(), LocalDateTime.now());
    }

    private WalmartComment commentWithBuyer(String id, String parentSku, LocalDate date, int star,
                                            String title, String content, String buyer) {
        return new WalmartComment("walmart", id, "110358320595211264", "PINKSIR",
                "item-" + id, parentSku, date, star, title, content, buyer,
                "product title", null, null, "US", null, LocalDateTime.now(), LocalDateTime.now());
    }
}
