package com.ai.walmartads.walmartcomment;

import com.ai.walmartads.config.LingXingProperties;
import com.ai.walmartads.importing.repository.SkuMappingRepository;
import com.ai.walmartads.walmartcomment.domain.WalmartComment;
import com.ai.walmartads.walmartcomment.repository.WalmartCommentRepository;
import com.ai.walmartads.walmartcomment.repository.WalmartCommentSyncLogRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 评价同步的落库语义单测。
 *
 * <p>核心风险是「整窗替换」会把买家事后编辑过的评论刷回旧版本 —— 故同步必须走
 * {@code (platform, comment_id)} upsert：新行 insert、旧行 update。
 * 这里直接驱动包级可见的 {@link WalmartCommentSyncService#persist} / {@code rescueUnmatched}，
 * 用真实的 {@link TransactionTemplate}（背后接 mock 的 {@code PlatformTransactionManager}）跑完事务边界，
 * 因此断言的是生产代码路径本身，不是重写的等价实现。
 *
 * <p>故意不用 {@code MockitoExtension}：共享 setup 里的部分 stub 只在部分用例命中，
 * 严格的 {@code UnnecessaryStubbing} 校验会把「用例无关」误报成失败；这里按用例自建 mock 即可。
 */
class WalmartCommentSyncUpsertTest {

    /** 首次入库时间；刻意取很旧的日期，使「upsert 刷新 syncedAt」的断言与运行日无关。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2020, 1, 1, 0, 0, 0);

    private WalmartCommentRepository commentRepository;
    private SkuMappingRepository skuMappingRepository;
    private WalmartCommentSyncService service;
    private ArgumentCaptor<WalmartComment> saved;

    @BeforeEach
    void setUp() {
        commentRepository = mock(WalmartCommentRepository.class);
        WalmartCommentSyncLogRepository logRepository = mock(WalmartCommentSyncLogRepository.class);
        skuMappingRepository = mock(SkuMappingRepository.class);
        // enabled=false → 构造函数不创建 LingXingClient，单测不触网。
        LingXingProperties properties = new LingXingProperties(
                false, null, null, null, 0, null, 0, List.of(), 0, null,
                null, 0, null, null);
        org.springframework.transaction.PlatformTransactionManager transactionManager =
                mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
        service = new WalmartCommentSyncService(properties, commentRepository, logRepository,
                skuMappingRepository, transactionManager, new ObjectMapper());
        saved = ArgumentCaptor.forClass(WalmartComment.class);
        when(commentRepository.save(any(WalmartComment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(commentRepository.saveAndFlush(any(WalmartComment.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void persistsInsertsNewRowsAndCountsStyleMapping() {
        when(commentRepository.findByPlatformAndCommentIdIn(eq("walmart"), any()))
                .thenReturn(List.of());
        when(skuMappingRepository.findCurrentParentSkuByItemIds(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{"11111111111", "PNK363055"}));

        WalmartCommentSyncService.WriteResult result = service.persist(List.of(
                row("c-1", "11111111111", "2026-09-01", 5, "great quality"),
                row("c-2", "22222222222", "2026-09-02", 2, "too thin fabric")
        ));

        assertThat(result.insertedCount()).isEqualTo(2);
        assertThat(result.updatedCount()).isEqualTo(0);
        // 只有 11111111111 在映射表中；22222222222 属历史下架款，保留 null 而不编造款号。
        assertThat(result.matchedSkuCount()).isEqualTo(1);
        assertThat(result.unmatchedCount()).isEqualTo(1);
        assertThat(result.avgStar()).isEqualByComparingTo(new BigDecimal("3.50"));
        verify(commentRepository, times(2)).save(saved.capture());
        verify(commentRepository, never()).saveAndFlush(any(WalmartComment.class));

        List<WalmartComment> entities = saved.getAllValues();
        assertThat(entities).extracting(WalmartComment::getCommentId).containsExactly("c-1", "c-2");
        assertThat(entities.get(0).getParentSku()).isEqualTo("PNK363055");
        assertThat(entities.get(0).getPlatform()).isEqualTo("walmart");
        assertThat(entities.get(0).getCommentDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(entities.get(0).getRawPayload())
                .contains("\"toolId\":\"platform_walmart_comment_list\"")
                .contains("\"source\":\"lingxing_mcp\"");
        assertThat(entities.get(1).getParentSku()).isNull();
        assertThat(entities.get(1).hasStyle()).isFalse();
    }

    @Test
    void persistsUpdatesExistingRowWithoutDuplicating() {
        WalmartComment existing = entity(
                "c-1", "11111111111", LocalDate.of(2026, 9, 1), 5, "great quality");
        when(commentRepository.findByPlatformAndCommentIdIn(eq("walmart"), any()))
                .thenReturn(List.of(existing));
        when(skuMappingRepository.findCurrentParentSkuByItemIds(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{"11111111111", "PNK363055"}));

        // 买家事后把 5 星改成了 1 星并重写内容 —— upsert 必须落到同一行。
        WalmartCommentSyncService.WriteResult result = service.persist(List.of(
                row("c-1", "11111111111", "2026-09-15", 1, "rewritten by buyer")
        ));

        assertThat(result.insertedCount()).isEqualTo(0);
        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.matchedSkuCount()).isEqualTo(1);
        verify(commentRepository, never()).save(any(WalmartComment.class));
        verify(commentRepository, times(1)).saveAndFlush(existing);

        assertThat(existing.getCommentStar()).isEqualTo(1);
        assertThat(existing.getCommentContent()).isEqualTo("rewritten by buyer");
        assertThat(existing.getParentSku()).isEqualTo("PNK363055");
        assertThat(existing.getCommentDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        // 业务主键与首次入库时间不得被覆盖，否则「同一评价」会被算成两次。
        assertThat(existing.getCommentId()).isEqualTo("c-1");
        assertThat(existing.getCreatedAt()).isEqualTo(NOW);
        assertThat(existing.getSyncedAt()).isAfter(NOW);
    }

    @Test
    void persistsSkipsRowsMissingBusinessKeyOrDate() {
        when(commentRepository.findByPlatformAndCommentIdIn(eq("walmart"), any()))
                .thenReturn(List.of());
        when(skuMappingRepository.findCurrentParentSkuByItemIds(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{"11111111111", "PNK363055"}));

        // 第一条无 comment_id（无法定位业务行）、第二条缺 comment_date（NOT NULL 约束）。
        Map<String, Object> orphan = row(null, "11111111111", "2026-09-01", 5, "no id");
        Map<String, Object> noDate = row("c-3", "11111111111", null, 5, "no date");

        WalmartCommentSyncService.WriteResult result = service.persist(List.of(
                orphan, noDate, row("c-4", "11111111111", "2026-09-03", 5, "ok")
        ));

        assertThat(result.insertedCount()).isEqualTo(1);
        assertThat(result.updatedCount()).isEqualTo(0);
        assertThat(result.avgStar()).isEqualByComparingTo(new BigDecimal("5.00"));
        verify(commentRepository, times(1)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(WalmartComment::getCommentId)
                .containsExactly("c-4");
    }

    @Test
    void rescueUnmatchedBackfillsParentSkuForHistoricalComments() {
        WalmartComment orphan = entity(
                "c-old", "17123456789", LocalDate.of(2025, 2, 10), 4, "early review");
        when(commentRepository.findByPlatformAndParentSkuIsNull(eq("walmart")))
                .thenReturn(List.of(orphan));
        when(skuMappingRepository.findCurrentParentSkuByItemIds(anyCollection()))
                .thenReturn(List.<Object[]>of(new Object[]{"17123456789", "PNK0079"}));

        int fixed = service.rescueUnmatched();

        assertThat(fixed).isEqualTo(1);
        assertThat(orphan.getParentSku()).isEqualTo("PNK0079");
        assertThat(orphan.hasStyle()).isTrue();
        verify(commentRepository, times(1)).save(orphan);
    }

    @Test
    void rescueUnmatchedIsNoOpWhenMappingIsStillMissing() {
        WalmartComment orphan = entity(
                "c-old", "17999999999", LocalDate.of(2025, 2, 10), 4, "still unmapped");
        when(commentRepository.findByPlatformAndParentSkuIsNull(eq("walmart")))
                .thenReturn(List.of(orphan));
        when(skuMappingRepository.findCurrentParentSkuByItemIds(anyCollection()))
                .thenReturn(List.of());

        assertThat(service.rescueUnmatched()).isEqualTo(0);
        assertThat(orphan.getParentSku()).isNull();
        verify(commentRepository, never()).save(any(WalmartComment.class));
    }

    @Test
    void rescueUnmatchedSkipsWhenNothingIsUnmatched() {
        when(commentRepository.findByPlatformAndParentSkuIsNull(eq("walmart"))).thenReturn(List.of());

        assertThat(service.rescueUnmatched()).isEqualTo(0);
        // 没有未归款数据时不应再查映射表 —— 避免每次同步的无谓 SQL。
        verify(skuMappingRepository, never()).findCurrentParentSkuByItemIds(anyCollection());
    }

    @Test
    void avgStarIsStableWhenNothingIsWritten() {
        when(commentRepository.findByPlatformAndCommentIdIn(eq("walmart"), any()))
                .thenReturn(List.of());

        WalmartCommentSyncService.WriteResult result = service.persist(List.of());

        assertThat(result.insertedCount()).isEqualTo(0);
        assertThat(result.updatedCount()).isEqualTo(0);
        assertThat(result.avgStar()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(commentRepository, never()).save(any(WalmartComment.class));
        verify(commentRepository, never()).saveAndFlush(any(WalmartComment.class));
    }

    private Map<String, Object> row(String id, String itemId, String commentDate, int star, String content) {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        if (id != null) {
            row.put("id", id);
        }
        row.put("platformProductNo", itemId);
        row.put("storeId", "110358320595211264");
        row.put("storeName", "PINKSIR");
        row.put("commentStar", star);
        if (commentDate != null) {
            row.put("commentDate", commentDate);
        }
        row.put("commentContent", content);
        row.put("buyerName", "buyer-" + id);
        row.put("siteCode", "US");
        return row;
    }

    private WalmartComment entity(String commentId, String itemId, LocalDate date, int star, String content) {
        return new WalmartComment("walmart", commentId, "110358320595211264", "PINKSIR", itemId,
                null, date, star, "title", content, "buyer", "product", null, null, "US",
                null, NOW, NOW);
    }
}
