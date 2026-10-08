package com.ai.walmartads.pricemonitor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 价格监控基线：每个变体 SKU 一行。
 *
 * <p>首见（first sight）时写入 baseline_price = last_price = 当前价；之后每小时读已被领星刷新的
 * {@code product_record.price}，与 {@code last_price} 比对，变动达标才落 {@code wm_ads_price_change}，
 * 并把 {@code last_price} 更新为最新价（累计偏离始终相对 {@code baseline_price} 计算）。
 */
@Entity
@Table(name = "wm_ads_price_baseline")
public class PriceBaseline extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

    @Id
    @Column(name = "sku", nullable = false)
    private String sku;

    @Column(name = "parent_sku")
    private String parentSku;

    @Column(name = "baseline_price", columnDefinition = "decimal(18,6)")
    private BigDecimal baselinePrice;

    @Column(name = "last_price", columnDefinition = "decimal(18,6)")
    private BigDecimal lastPrice;

    @Column(name = "first_seen_at", columnDefinition = "datetime")
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at", columnDefinition = "datetime")
    private LocalDateTime lastSeenAt;

    protected PriceBaseline() {
    }

    public PriceBaseline(String sku, String parentSku, BigDecimal baselinePrice,
                         BigDecimal lastPrice, LocalDateTime firstSeenAt, LocalDateTime lastSeenAt) {
        this.sku = sku;
        this.parentSku = parentSku;
        this.baselinePrice = baselinePrice;
        this.lastPrice = lastPrice;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
    }

    public String getSku() {
        return sku;
    }

    public String getParentSku() {
        return parentSku;
    }

    public void setParentSku(String parentSku) {
        this.parentSku = parentSku;
    }

    public BigDecimal getBaselinePrice() {
        return baselinePrice;
    }

    public BigDecimal getLastPrice() {
        return lastPrice;
    }

    public void setLastPrice(BigDecimal lastPrice) {
        this.lastPrice = lastPrice;
    }

    public LocalDateTime getFirstSeenAt() {
        return firstSeenAt;
    }

    public LocalDateTime getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(LocalDateTime lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }
}
