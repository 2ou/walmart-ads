package com.ai.walmartads.walmartlisting.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 沃尔玛在线商品 MCP 同步落库记录。每次同步（CRON 或 MANUAL）写一行，便于排查与审计。
 * inserted_count 恒为 0：同步只回写产品库中已存在的行，不新建 SKU。
 */
@Entity
@Table(name = "wm_ads_walmart_listing_sync_log")
public class WalmartListingSyncLog extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    @Column
    private LocalDateTime finishedAt;

    /** SUCCESS / FAILED / PARTIAL */
    @Column(nullable = false, length = 20)
    private String status;

    /** 数据来源，恒为 lingxing_mcp */
    @Column(nullable = false, length = 40)
    private String source;

    @Column(nullable = false)
    private int fetchedCount;

    @Column(nullable = false)
    private int updatedCount;

    /** 恒为 0（只回写已存在行） */
    @Column(nullable = false)
    private int insertedCount;

    @Column(columnDefinition = "text")
    private String errorMessage;

    /** CRON / MANUAL */
    @Column(nullable = false, length = 20)
    private String triggeredBy;

    @Column(nullable = false)
    private long durationMs;

    protected WalmartListingSyncLog() {
    }

    public WalmartListingSyncLog(LocalDateTime startedAt, String source, String triggeredBy) {
        this.startedAt = startedAt;
        this.source = source;
        this.triggeredBy = triggeredBy;
        this.fetchedCount = 0;
        this.updatedCount = 0;
        this.insertedCount = 0;
        this.status = "RUNNING";
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

    public int getFetchedCount() {
        return fetchedCount;
    }

    public void setFetchedCount(int fetchedCount) {
        this.fetchedCount = fetchedCount;
    }

    public int getUpdatedCount() {
        return updatedCount;
    }

    public void setUpdatedCount(int updatedCount) {
        this.updatedCount = updatedCount;
    }

    public int getInsertedCount() {
        return insertedCount;
    }

    public void setInsertedCount(int insertedCount) {
        this.insertedCount = insertedCount;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(long durationMs) {
        this.durationMs = durationMs;
    }
}
