package com.ai.walmartads.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 按款号（parentSku）维护的「日均销量(手动)」覆盖。
 *
 * <p>日均销量原本由订单实际销量 + 报表 daily_units_sold 加权派生，
 * 现在允许在款号维度手填覆盖（整个款下所有 SKU 套用同一值）。
 * 列为 null 时计算器回退到系统派生值——可逆、不会算崩。</p>
 */
@Entity
@Table(name = "wm_ads_forecast_factor_daily_parent")
public class ParentDailySalesOverride extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

    @Id
    @Column(name = "parent_sku", length = 180, nullable = false)
    /** 款号（因子键，唯一） */
    private String parentSku;

    @Column(name = "manual_daily_sales", precision = 18, scale = 6)
    /** 手动覆盖的日均销量（件/天）；null=使用系统派生值 */
    private BigDecimal manualDailySales;

    @Column(name = "updated_at", nullable = false)
    /** 更新时间 */
    private LocalDateTime updatedAt;

    protected ParentDailySalesOverride() {
    }

    public ParentDailySalesOverride(String parentSku) {
        this.parentSku = parentSku;
        this.updatedAt = LocalDateTime.now();
    }

    public String getParentSku() {
        return parentSku;
    }

    public void setParentSku(String parentSku) {
        this.parentSku = parentSku;
    }

    public BigDecimal getManualDailySales() {
        return manualDailySales;
    }

    public void setManualDailySales(BigDecimal manualDailySales) {
        this.manualDailySales = manualDailySales;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
