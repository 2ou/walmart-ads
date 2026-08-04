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
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "wm_ads_inventory_snapshot")
public class InventorySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 主键，自增 */
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_file_id", nullable = false)
    /** 关联导入文件ID */
    private ImportFile importFile;

    @Column(nullable = false)
    /** 快照时间 */
    private LocalDateTime snapshotAt;

    @Column(nullable = false, length = 180)
    /** 商品 SKU */
    private String sku;

    @Column(length = 80)
    /** 父 SKU（可空） */
    private String parentSku;

    @Column(length = 80)
    /** 商品ID（可空） */
    private String itemId;

    @Column(length = 40)
    /** GTIN（可空） */
    private String gtin;

    @Column(columnDefinition = "text")
    /** 商品名称（可空） */
    private String itemName;

    @Column(length = 80)
    /** 状态（可空） */
    private String status;

    @Column(precision = 18, scale = 6)
    /** 日均销售额 */
    private BigDecimal dailySales;

    @Column(precision = 18, scale = 6)
    /** 日均销量（件） */
    private BigDecimal dailyUnitsSold;

    @Column(precision = 18, scale = 4)
    /** 可用库存（件） */
    private BigDecimal availableUnits;

    @Column(precision = 18, scale = 4)
    /** 预留库存（件） */
    private BigDecimal reservedUnits;

    @Column(precision = 18, scale = 4)
    /** 在途库存（件） */
    private BigDecimal inboundUnits;

    @Column(precision = 18, scale = 4)
    /** 预计可售天数 */
    private BigDecimal forecastedDaysSupply;
    /** 预计缺货日期（可空） */
    private LocalDate outOfStockDate;

    @Column(precision = 18, scale = 4)
    /** 建议补货量（件） */
    private BigDecimal suggestedUnits;

    @Column(length = 120)
    /** 品牌（可空） */
    private String brand;

    @Column(precision = 18, scale = 4)
    /** 冗余库存（件） */
    private BigDecimal surplusUnits;

    @Column(precision = 18, scale = 8)
    /** 售罄率 */
    private BigDecimal sellThroughRate;

    @Column(length = 80)
    /** 上架状态（可空） */
    private String publishingStatus;

    @Column(length = 80)
    /** 商品生命周期阶段（可空） */
    private String itemLifecycle;

    @Column(nullable = false, columnDefinition = "json")
    /** 原始报文（JSON） */
    private String rawPayload;

    @Column(nullable = false)
    /** 创建时间 */
    private LocalDateTime createdAt;

    protected InventorySnapshot() {
    }

    public InventorySnapshot(
            ImportFile importFile,
            LocalDateTime snapshotAt,
            String sku,
            String parentSku,
            String itemId,
            String gtin,
            String itemName,
            String status,
            BigDecimal dailySales,
            BigDecimal dailyUnitsSold,
            BigDecimal availableUnits,
            BigDecimal reservedUnits,
            BigDecimal inboundUnits,
            BigDecimal forecastedDaysSupply,
            LocalDate outOfStockDate,
            BigDecimal suggestedUnits,
            String brand,
            BigDecimal surplusUnits,
            BigDecimal sellThroughRate,
            String publishingStatus,
            String itemLifecycle,
            String rawPayload
    ) {
        this.importFile = importFile;
        this.snapshotAt = snapshotAt;
        this.sku = sku;
        this.parentSku = parentSku;
        this.itemId = itemId;
        this.gtin = gtin;
        this.itemName = itemName;
        this.status = status;
        this.dailySales = dailySales;
        this.dailyUnitsSold = dailyUnitsSold;
        this.availableUnits = availableUnits;
        this.reservedUnits = reservedUnits;
        this.inboundUnits = inboundUnits;
        this.forecastedDaysSupply = forecastedDaysSupply;
        this.outOfStockDate = outOfStockDate;
        this.suggestedUnits = suggestedUnits;
        this.brand = brand;
        this.surplusUnits = surplusUnits;
        this.sellThroughRate = sellThroughRate;
        this.publishingStatus = publishingStatus;
        this.itemLifecycle = itemLifecycle;
        this.rawPayload = rawPayload;
        this.createdAt = LocalDateTime.now();
    }

    public String getSku() {
        return sku;
    }

    public String getParentSku() {
        return parentSku;
    }

    public LocalDateTime getSnapshotAt() {
        return snapshotAt;
    }

    public String getItemId() {
        return itemId;
    }

    public BigDecimal getAvailableUnits() {
        return availableUnits;
    }

    public BigDecimal getReservedUnits() {
        return reservedUnits;
    }

    public BigDecimal getInboundUnits() {
        return inboundUnits;
    }

    public BigDecimal getForecastedDaysSupply() {
        return forecastedDaysSupply;
    }

    public BigDecimal getSuggestedUnits() {
        return suggestedUnits;
    }

    public String getRawPayload() {
        return rawPayload;
    }
}
