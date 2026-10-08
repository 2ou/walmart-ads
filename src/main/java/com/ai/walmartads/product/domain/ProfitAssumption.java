package com.ai.walmartads.product.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "wm_ads_profit_assumption")
public class ProfitAssumption extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

    @Id
    @Column(name = "parent_sku", length = 80)
    /** 父 SKU（主键） */
    private String parentSku;

    @Column(columnDefinition = "decimal(18,6)")
    /** 采购成本（¥） */
    private BigDecimal purchaseCost;

    @Column(columnDefinition = "decimal(18,6)")
    /** 头程费用（¥） */
    private BigDecimal firstLeg;

    @Column(columnDefinition = "decimal(18,6)")
    /** 销售数量（件） */
    private BigDecimal salesQty;

    @Column(nullable = false, columnDefinition = "decimal(18,6)")
    private BigDecimal adCost = BigDecimal.ONE;

    @Column(columnDefinition = "decimal(10,6)")
    /** 退款率（可空，0~1） */
    private BigDecimal refundRate;

    @Column(name = "prefer_manual_refund_rate", nullable = false)
    /** 是否优先采用手工退款率；默认使用款号累计总退款率 */
    private boolean preferManualRefundRate;

    @Column(columnDefinition = "decimal(18,6)")
    /** 手工配送费（可空，整款级兜底：产品库无 ItemReport 重量反算后改由此值承担） */
    private BigDecimal deliveryFee;

    @Column(nullable = false)
    /** 创建时间 */
    private LocalDateTime createdAt;

    @Column(nullable = false)
    /** 更新时间 */
    private LocalDateTime updatedAt;

    protected ProfitAssumption() {
    }

    public ProfitAssumption(String parentSku) {
        this.parentSku = parentSku;
        this.adCost = BigDecimal.ONE;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public String getParentSku() {
        return parentSku;
    }

    public BigDecimal getPurchaseCost() {
        return purchaseCost;
    }

    public void setPurchaseCost(BigDecimal purchaseCost) {
        this.purchaseCost = purchaseCost;
    }

    public BigDecimal getFirstLeg() {
        return firstLeg;
    }

    public void setFirstLeg(BigDecimal firstLeg) {
        this.firstLeg = firstLeg;
    }

    public BigDecimal getSalesQty() {
        return salesQty;
    }

    public void setSalesQty(BigDecimal salesQty) {
        this.salesQty = salesQty;
    }

    public BigDecimal getAdCost() {
        return adCost;
    }

    public void setAdCost(BigDecimal adCost) {
        this.adCost = adCost == null ? BigDecimal.ONE : adCost;
    }

    public BigDecimal getRefundRate() {
        return refundRate;
    }

    public void setRefundRate(BigDecimal refundRate) {
        this.refundRate = refundRate;
    }

    public boolean isPreferManualRefundRate() {
        return preferManualRefundRate;
    }

    public void setPreferManualRefundRate(boolean preferManualRefundRate) {
        this.preferManualRefundRate = preferManualRefundRate;
    }

    public BigDecimal getDeliveryFee() {
        return deliveryFee;
    }

    public void setDeliveryFee(BigDecimal deliveryFee) {
        this.deliveryFee = deliveryFee;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
