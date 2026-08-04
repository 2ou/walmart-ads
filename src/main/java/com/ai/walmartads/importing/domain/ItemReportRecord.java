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

@Entity
@Table(name = "wm_ads_item_report_record")
public class ItemReportRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 主键，自增 */
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_file_id", nullable = false)
    /** 关联导入文件ID */
    private ImportFile importFile;

    @Column(nullable = false)
    /** 源数据更新时间 */
    private LocalDateTime sourceUpdatedAt;

    @Column(nullable = false)
    /** 是否当前版本（1是/0否） */
    private boolean currentVersion;

    @Column(nullable = false, length = 180)
    /** 商品 SKU */
    private String sku;

    @Column(length = 80)
    /** 父 SKU（可空） */
    private String parentSku;

    @Column(length = 40)
    /** 商品ID（可空） */
    private String itemId;

    @Column(length = 40)
    /** UPC（可空） */
    private String upc;

    @Column(length = 40)
    /** WPID（可空） */
    private String wpid;

    @Column(length = 40)
    /** GTIN（可空） */
    private String gtin;

    @Column(columnDefinition = "text")
    /** 商品名称（可空） */
    private String productName;

    @Column(length = 120)
    /** 品牌（可空） */
    private String brand;

    @Column(length = 255)
    /** 商品类目（可空） */
    private String productCategory;

    @Column(length = 40)
    /** 生命周期状态（可空） */
    private String lifecycleStatus;

    @Column(length = 40)
    /** 履约类型（可空） */
    private String fulfillmentType;

    @Column(nullable = false, columnDefinition = "decimal(18,6)")
    /** 商品价格 */
    private BigDecimal price;

    @Column(length = 10)
    /** 币种（可空） */
    private String currency;

    @Column(length = 40)
    /** 价格类型（可空） */
    private String priceType;

    @Column(nullable = false, columnDefinition = "decimal(18,6)")
    /** 物流重量（磅） */
    private BigDecimal shippingWeightLb;

    @Column(length = 10)
    /** 物流重量单位（可空） */
    private String shippingWeightUnit;

    @Column(nullable = false, columnDefinition = "decimal(18,6)")
    /** 物流重量（千克） */
    private BigDecimal shippingWeightKg;

    @Column(nullable = false, columnDefinition = "decimal(18,6)")
    /** 计费重量（磅） */
    private BigDecimal billingWeightLb;

    @Column(nullable = false, columnDefinition = "decimal(18,6)")
    /** 基础配送费 */
    private BigDecimal baseFee;

    @Column(nullable = false, columnDefinition = "decimal(18,6)")
    /** 服装附加费 */
    private BigDecimal apparelSurcharge;

    @Column(nullable = false, columnDefinition = "decimal(18,6)")
    /** 低价附加费 */
    private BigDecimal lowPriceSurcharge;

    @Column(nullable = false, columnDefinition = "decimal(18,6)")
    /** 配送费（合计） */
    private BigDecimal deliveryFee;

    @Column(nullable = false, columnDefinition = "json")
    /** 原始报文（JSON） */
    private String rawPayload;

    @Column(nullable = false)
    /** 创建时间 */
    private LocalDateTime createdAt;

    protected ItemReportRecord() {
    }

    public ItemReportRecord(
            ImportFile importFile,
            LocalDateTime sourceUpdatedAt,
            String sku,
            String parentSku,
            String itemId,
            String upc,
            String wpid,
            String gtin,
            String productName,
            String brand,
            String productCategory,
            String lifecycleStatus,
            String fulfillmentType,
            BigDecimal price,
            String currency,
            String priceType,
            BigDecimal shippingWeightLb,
            String shippingWeightUnit,
            BigDecimal shippingWeightKg,
            BigDecimal billingWeightLb,
            BigDecimal baseFee,
            BigDecimal apparelSurcharge,
            BigDecimal lowPriceSurcharge,
            BigDecimal deliveryFee,
            String rawPayload
    ) {
        this.importFile = importFile;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.currentVersion = true;
        this.sku = sku;
        this.parentSku = parentSku;
        this.itemId = itemId;
        this.upc = upc;
        this.wpid = wpid;
        this.gtin = gtin;
        this.productName = productName;
        this.brand = brand;
        this.productCategory = productCategory;
        this.lifecycleStatus = lifecycleStatus;
        this.fulfillmentType = fulfillmentType;
        this.price = price;
        this.currency = currency;
        this.priceType = priceType;
        this.shippingWeightLb = shippingWeightLb;
        this.shippingWeightUnit = shippingWeightUnit;
        this.shippingWeightKg = shippingWeightKg;
        this.billingWeightLb = billingWeightLb;
        this.baseFee = baseFee;
        this.apparelSurcharge = apparelSurcharge;
        this.lowPriceSurcharge = lowPriceSurcharge;
        this.deliveryFee = deliveryFee;
        this.rawPayload = rawPayload;
        this.createdAt = LocalDateTime.now();
    }

    public String getSku() {
        return sku;
    }

    public String getRawPayload() {
        return rawPayload;
    }
}
