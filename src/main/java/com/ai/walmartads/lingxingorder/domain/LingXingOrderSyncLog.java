package com.ai.walmartads.lingxingorder.domain;

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
 * 领星 MCP 销量/订单日粒度同步落库记录。每次同步（CRON 或 MANUAL）写一行，便于排查与审计。
 */
@Entity
@Table(name = "wm_ads_lingxing_order_sync_log")
public class LingXingOrderSyncLog extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDateTime startedAt;

    @Column
    private LocalDateTime finishedAt;

    /** RUNNING / SUCCESS / PARTIAL / FAILED */
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

    /** 窗口内有销 SKU 数（去重） */
    @Column(nullable = false)
    private int fetchedSkuCount;

    /** 实际入库的日期数 */
    @Column(nullable = false)
    private int coveredDayCount;

    /** 写入 wm_ads_order_record 的行数 */
    @Column(nullable = false)
    private int insertedCount;

    /** 清理的旧领星订单行数 */
    @Column(nullable = false)
    private int deletedCount;

    /** 店铺级订单量合计（口径核对用真值） */
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal storeOrderTotal;

    @Column(nullable = false)
    private long durationMs;

    @Column(columnDefinition = "text")
    private String errorMessage;

    protected LingXingOrderSyncLog() {
    }

    public LingXingOrderSyncLog(
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
        this.storeOrderTotal = BigDecimal.ZERO;
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

    public int getFetchedSkuCount() {
        return fetchedSkuCount;
    }

    public void setFetchedSkuCount(int fetchedSkuCount) {
        this.fetchedSkuCount = fetchedSkuCount;
    }

    public int getCoveredDayCount() {
        return coveredDayCount;
    }

    public void setCoveredDayCount(int coveredDayCount) {
        this.coveredDayCount = coveredDayCount;
    }

    public int getInsertedCount() {
        return insertedCount;
    }

    public void setInsertedCount(int insertedCount) {
        this.insertedCount = insertedCount;
    }

    public int getDeletedCount() {
        return deletedCount;
    }

    public void setDeletedCount(int deletedCount) {
        this.deletedCount = deletedCount;
    }

    public BigDecimal getStoreOrderTotal() {
        return storeOrderTotal;
    }

    public void setStoreOrderTotal(BigDecimal storeOrderTotal) {
        this.storeOrderTotal = storeOrderTotal == null ? BigDecimal.ZERO : storeOrderTotal;
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
