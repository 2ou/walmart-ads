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

import java.time.LocalDateTime;

@Entity
@Table(name = "wm_ads_product_record")
public class ProductRecord {

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

    @Column(length = 160)
    /** 商品类型（可空） */
    private String productType;

    @Column(length = 80)
    /** 商品ID（可空） */
    private String productId;

    @Column(length = 40)
    /** 商品ID类型（可空） */
    private String productIdType;

    @Column(columnDefinition = "text")
    /** 商品名称（可空） */
    private String productName;

    @Column(length = 120)
    /** 品牌（可空） */
    private String brand;

    @Column(columnDefinition = "mediumtext")
    /** 简短描述（可空） */
    private String shortDescription;

    @Column(columnDefinition = "json")
    /** 关键卖点（JSON，可空） */
    private String keyFeatures;

    @Column(length = 2500)
    /** 主图URL（可空） */
    private String mainImageUrl;

    @Column(columnDefinition = "json")
    /** 副图URL列表（JSON，可空） */
    private String secondaryImageUrls;

    @Column(length = 600)
    /** 颜色（可空） */
    private String color;

    @Column(length = 600)
    /** 颜色类目（可空） */
    private String colorCategory;

    @Column(length = 120)
    /** 尺码（可空） */
    private String size;

    @Column(length = 120)
    /** 尺码组（可空） */
    private String sizeGroup;

    @Column(length = 600)
    /** 图案名称（可空） */
    private String patternName;

    @Column(length = 4000)
    /** 材质名称（可空） */
    private String materialName;

    @Column(length = 80)
    /** 适用人群/性别（可空） */
    private String gender;

    @Column(length = 300)
    /** 变体组ID（可空） */
    private String variantGroupId;

    /** 裁床数：一条颜色的布能裁多少件，用于做货数量下单参考（可空，未填不参与计算）。 */
    @Column(name = "cutting_yield")
    private Integer cuttingYield;

    @Column(nullable = false, columnDefinition = "json")
    /** 原始报文（JSON） */
    private String rawPayload;

    @Column(nullable = false)
    /** 创建时间 */
    private LocalDateTime createdAt;

    protected ProductRecord() {
    }

    public ProductRecord(
            ImportFile importFile,
            LocalDateTime sourceUpdatedAt,
            String sku,
            String parentSku,
            String productType,
            String productId,
            String productIdType,
            String productName,
            String brand,
            String shortDescription,
            String keyFeatures,
            String mainImageUrl,
            String secondaryImageUrls,
            String color,
            String colorCategory,
            String size,
            String sizeGroup,
            String patternName,
            String materialName,
            String gender,
            String variantGroupId,
            String rawPayload
    ) {
        this.importFile = importFile;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.currentVersion = true;
        this.sku = sku;
        this.parentSku = parentSku;
        this.productType = productType;
        this.productId = productId;
        this.productIdType = productIdType;
        this.productName = productName;
        this.brand = brand;
        this.shortDescription = shortDescription;
        this.keyFeatures = keyFeatures;
        this.mainImageUrl = mainImageUrl;
        this.secondaryImageUrls = secondaryImageUrls;
        this.color = color;
        this.colorCategory = colorCategory;
        this.size = size;
        this.sizeGroup = sizeGroup;
        this.patternName = patternName;
        this.materialName = materialName;
        this.gender = gender;
        this.variantGroupId = variantGroupId;
        this.rawPayload = rawPayload;
        this.createdAt = LocalDateTime.now();
    }

    public String getSku() {
        return sku;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public Integer getCuttingYield() {
        return cuttingYield;
    }

    public void setCuttingYield(Integer cuttingYield) {
        this.cuttingYield = cuttingYield;
    }
}
