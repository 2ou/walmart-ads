package com.ai.walmartads.importing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 需求预测计划值（仅 Excel 独有字段）。
 * 实际值（颜色、品名、WFS 可售、近 30 天销量、近 3 月日均、周转天数、退货率）由系统现有数据源关联，不在此表存储。
 * 按 SKU 版本化：重新导入时先作废同 SKU 旧版本，再写入新版本。
 */
@Entity
@Table(name = "wm_ads_demand_forecast")
public class DemandForecastRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 主键，自增 */
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_file_id", nullable = false)
    /** 关联导入文件ID */
    private ImportFile importFile;

    @Column(nullable = false)
    /** 是否当前版本（1是/0否） */
    private boolean currentVersion;

    @Column(nullable = false, length = 180)
    /** 商品 SKU（计划值键） */
    private String sku;

    @Column(precision = 18, scale = 4)
    /** 预计销量(PMC)（件，可空） */
    private BigDecimal forecastPmc;

    @Column(precision = 18, scale = 4)
    /** 预计销量(运营)（件，可空） */
    private BigDecimal forecastOps;

    @Column(precision = 18, scale = 6)
    /** 百分比增益（小数，如 0.2=20%，可空） */
    private BigDecimal pctGain;

    @Column(precision = 18, scale = 6)
    /** 退货重复利用率（小数，可空） */
    private BigDecimal reuseRate;

    @Column(precision = 18, scale = 4)
    /** 需求计划（件，可空） */
    private BigDecimal demandPlan;

    @Column(precision = 18, scale = 4)
    /** 实际下单量（件，可空） */
    private BigDecimal actualOrder;

    @Column(precision = 18, scale = 4)
    /** 发货量（件，可空） */
    private BigDecimal shipQty;

    @Column(precision = 18, scale = 4)
    /** 剩余（件，可空） */
    private BigDecimal remaining;

    @Column(nullable = false, columnDefinition = "json")
    /** 原始报文（JSON） */
    private String rawPayload;

    @Column(nullable = false)
    /** 创建时间 */
    private LocalDateTime createdAt;

    protected DemandForecastRecord() {
    }

    public DemandForecastRecord(
            ImportFile importFile,
            String sku,
            BigDecimal forecastPmc,
            BigDecimal forecastOps,
            BigDecimal pctGain,
            BigDecimal reuseRate,
            BigDecimal demandPlan,
            BigDecimal actualOrder,
            BigDecimal shipQty,
            BigDecimal remaining,
            String rawPayload
    ) {
        this.importFile = importFile;
        this.currentVersion = true;
        this.sku = sku;
        this.forecastPmc = forecastPmc;
        this.forecastOps = forecastOps;
        this.pctGain = pctGain;
        this.reuseRate = reuseRate;
        this.demandPlan = demandPlan;
        this.actualOrder = actualOrder;
        this.shipQty = shipQty;
        this.remaining = remaining;
        this.rawPayload = rawPayload;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public BigDecimal getForecastPmc() {
        return forecastPmc;
    }

    public BigDecimal getForecastOps() {
        return forecastOps;
    }

    public BigDecimal getPctGain() {
        return pctGain;
    }

    public BigDecimal getReuseRate() {
        return reuseRate;
    }

    public BigDecimal getDemandPlan() {
        return demandPlan;
    }

    public BigDecimal getActualOrder() {
        return actualOrder;
    }

    public BigDecimal getShipQty() {
        return shipQty;
    }

    public BigDecimal getRemaining() {
        return remaining;
    }

    public String getRawPayload() {
        return rawPayload;
    }
}
