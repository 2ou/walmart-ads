package com.ai.walmartads.pricemonitor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 价格变动记录：每次检测到价格变动（相对 last_price 的环比幅度 ≥ 阈值）落一条。
 *
 * <p>{@code change_pct} 为环比变动（(新-旧)/旧*100），{@code cumulative_pct} 为相对基线价的累计偏离；
 * {@code abnormal} 标记是否为异常（|change_pct| > abnormal-percent，默认 20%）——
 * 正常（绿）仅展示，异常（红）需手动「标记已处理」才从活跃列表消失。
 */
@Entity
@Table(name = "wm_ads_price_change")
public class PriceChange extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "sku", nullable = false)
    private String sku;

    @Column(name = "parent_sku")
    private String parentSku;

    @Column(name = "old_price", columnDefinition = "decimal(18,6)")
    private BigDecimal oldPrice;

    @Column(name = "new_price", columnDefinition = "decimal(18,6)")
    private BigDecimal newPrice;

    @Column(name = "change_pct", columnDefinition = "decimal(10,4)")
    private BigDecimal changePct;

    @Column(name = "cumulative_pct", columnDefinition = "decimal(10,4)")
    private BigDecimal cumulativePct;

    @Column(name = "detected_at", columnDefinition = "datetime", nullable = false)
    private LocalDateTime detectedAt;

    @Column(name = "acknowledged", nullable = false)
    private boolean acknowledged;

    @Column(name = "abnormal", nullable = false)
    private boolean abnormal;

    protected PriceChange() {
    }

    public PriceChange(String sku, String parentSku, BigDecimal oldPrice, BigDecimal newPrice,
                       BigDecimal changePct, BigDecimal cumulativePct, LocalDateTime detectedAt,
                       boolean acknowledged, boolean abnormal) {
        this.sku = sku;
        this.parentSku = parentSku;
        this.oldPrice = oldPrice;
        this.newPrice = newPrice;
        this.changePct = changePct;
        this.cumulativePct = cumulativePct;
        this.detectedAt = detectedAt;
        this.acknowledged = acknowledged;
        this.abnormal = abnormal;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getParentSku() {
        return parentSku;
    }

    public BigDecimal getOldPrice() {
        return oldPrice;
    }

    public BigDecimal getNewPrice() {
        return newPrice;
    }

    public BigDecimal getChangePct() {
        return changePct;
    }

    public BigDecimal getCumulativePct() {
        return cumulativePct;
    }

    public LocalDateTime getDetectedAt() {
        return detectedAt;
    }

    public boolean isAcknowledged() {
        return acknowledged;
    }

    public void setAcknowledged(boolean acknowledged) {
        this.acknowledged = acknowledged;
    }

    public boolean isAbnormal() {
        return abnormal;
    }
}
