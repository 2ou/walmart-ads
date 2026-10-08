package com.ai.walmartads.walmartcomment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 领星 Walmart 评价同步落库记录。每次同步（CRON / MANUAL / BACKFILL）写一行，便于排查与审计。
 * 字段口径与 {@code wm_ads_lingxing_order_sync_log} 保持一致，复用「最近一次同步 / 补窗」逻辑。
 */
@Entity
@Table(name = "wm_ads_lingxing_comment_sync_log")
public class WalmartCommentSyncLog extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    @Column
    private LocalDateTime finishedAt;

    /** RUNNING / SUCCESS / FAILED */
    @Column(nullable = false, length = 20)
    private String status;

    /** 数据来源，恒为 lingxing_mcp */
    @Column(nullable = false, length = 40)
    private String source;

    /** CRON / MANUAL / BACKFILL */
    @Column(nullable = false, length = 20)
    private String triggeredBy;

    @Column(nullable = false)
    private LocalDate windowStart;

    @Column(nullable = false)
    private LocalDate windowEnd;

    /** 窗口内领星返回的评价条数 */
    @Column(nullable = false)
    private int fetchedCount;

    /** 新写入条数 */
    @Column(nullable = false)
    private int insertedCount;

    /** 内容更新（upsert 命中）条数 */
    @Column(nullable = false)
    private int updatedCount;

    /** 归到款号的条数 */
    @Column(nullable = false)
    private int matchedSkuCount;

    /** 未归款条数（历史下架款） */
    @Column(nullable = false)
    private int unmatchedCount;

    /** 本次窗口均分（口径核对用真值） */
    @Column(nullable = false, precision = 4, scale = 2)
    private BigDecimal avgStar;

    @Column(nullable = false)
    private long durationMs;

    @Column(columnDefinition = "text")
    private String errorMessage;

    protected WalmartCommentSyncLog() {
    }

    public WalmartCommentSyncLog(
            LocalDateTime startedAt,
            String source,
            String triggeredBy,
            LocalDate windowStart,
            LocalDate windowEnd
    ) {
        this.startedAt = startedAt;
        this.source = source;
        this.triggeredBy = triggeredBy;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.status = "RUNNING";
        this.avgStar = BigDecimal.ZERO;
    }

    public Long getId() {
        return id;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSource() {
        return source;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public LocalDate getWindowStart() {
        return windowStart;
    }

    public LocalDate getWindowEnd() {
        return windowEnd;
    }

    public int getFetchedCount() {
        return fetchedCount;
    }

    public void setFetchedCount(int fetchedCount) {
        this.fetchedCount = fetchedCount;
    }

    public int getInsertedCount() {
        return insertedCount;
    }

    public void setInsertedCount(int insertedCount) {
        this.insertedCount = insertedCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public void setUpdatedCount(int updatedCount) {
        this.updatedCount = updatedCount;
    }

    public int getMatchedSkuCount() {
        return matchedSkuCount;
    }

    public void setMatchedSkuCount(int matchedSkuCount) {
        this.matchedSkuCount = matchedSkuCount;
    }

    public int getUnmatchedCount() {
        return unmatchedCount;
    }

    public void setUnmatchedCount(int unmatchedCount) {
        this.unmatchedCount = unmatchedCount;
    }

    public BigDecimal getAvgStar() {
        return avgStar;
    }

    public void setAvgStar(BigDecimal avgStar) {
        this.avgStar = avgStar == null ? BigDecimal.ZERO : avgStar;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
