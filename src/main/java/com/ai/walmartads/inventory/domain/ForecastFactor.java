package com.ai.walmartads.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 补货预测输入因子（系统维护，独立于需求预测 Excel 导入）。
 *
 * <p>为什么是独立表而不是放进 wm_ads_demand_forecast：后者按导入版本化，重导会作废旧行，
 * 用户在内联编辑里维护的因子会被一并清除。这里用 sku 做主键、非版本化，
 * 保证「完全在系统内维护因子」的诉求落地，且重导需求预测表单不会丢因子。</p>
 *
 * <p>任何因子为 null 时，计算器自动取默认值（季节性系数=1、其余天数/比率=0），
 * 公式退化为原速度法——可逆、不会算崩。</p>
 */
@Entity
@Table(name = "wm_ads_forecast_factor")
public class ForecastFactor extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

    @Id
    @Column(name = "sku", length = 180, nullable = false)
    /** 商品 SKU（因子键，唯一） */
    private String sku;

    @Column(name = "seasonal_coef", precision = 18, scale = 6)
    /** 季节性系数（乘数，默认 1.0） */
    private BigDecimal seasonalCoef;

    @Column(name = "gain_days", precision = 10, scale = 2)
    /** 增益天数（叠加进目标覆盖天数，默认 0） */
    private BigDecimal gainDays;

    @Column(name = "safety_days", precision = 10, scale = 2)
    /** 安全库存天数（叠加进目标覆盖天数，默认 0） */
    private BigDecimal safetyDays;

    @Column(name = "return_rate", precision = 18, scale = 6)
    /** 退货率（0~1，默认 0） */
    private BigDecimal returnRate;

    @Column(name = "return_reuse_rate", precision = 18, scale = 6)
    /** 退货重复利用率（0~1，默认 0） */
    private BigDecimal returnReuseRate;

    @Column(name = "updated_at", nullable = false)
    /** 更新时间 */
    private LocalDateTime updatedAt;

    protected ForecastFactor() {
    }

    public ForecastFactor(String sku) {
        this.sku = sku;
        this.updatedAt = LocalDateTime.now();
    }

    public String getSku() {
        return sku;
    }

    public BigDecimal getSeasonalCoef() {
        return seasonalCoef;
    }

    public void setSeasonalCoef(BigDecimal seasonalCoef) {
        this.seasonalCoef = seasonalCoef;
    }

    public BigDecimal getGainDays() {
        return gainDays;
    }

    public void setGainDays(BigDecimal gainDays) {
        this.gainDays = gainDays;
    }

    public BigDecimal getSafetyDays() {
        return safetyDays;
    }

    public void setSafetyDays(BigDecimal safetyDays) {
        this.safetyDays = safetyDays;
    }

    public BigDecimal getReturnRate() {
        return returnRate;
    }

    public void setReturnRate(BigDecimal returnRate) {
        this.returnRate = returnRate;
    }

    public BigDecimal getReturnReuseRate() {
        return returnReuseRate;
    }

    public void setReturnReuseRate(BigDecimal returnReuseRate) {
        this.returnReuseRate = returnReuseRate;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
