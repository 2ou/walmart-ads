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
@Table(name = "wm_ads_product_record")
public class ProductRecord extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

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

    /** 开售时间：该父款最早有销量日；无销量为 null（新品）。由订单表最早成交日回填，按父款去重。 */
    @Column(name = "launch_date")
    private LocalDate launchDate;

    /** 沃尔玛在线列表价格（USD，可空），由「沃尔玛商品」扫描回写到产品库。 */
    @Column(columnDefinition = "decimal(18,6)")
    private BigDecimal price;

    /** 沃尔玛在线列表划线价（USD，可空），由「沃尔玛商品」扫描回写到产品库。 */
    @Column(name = "strikethrough_price", columnDefinition = "decimal(18,6)")
    private BigDecimal strikethroughPrice;

    /** UPC（可空），由「沃尔玛商品」扫描回写。 */
    @Column(length = 40)
    private String upc;

    /** GTIN（可空），由「沃尔玛商品」扫描回写。 */
    @Column(length = 40)
    private String gtin;

    /** 上架状态（可空，如 PUBLISHED/UNPUBLISHED），由「沃尔玛商品」扫描回写。 */
    @Column(name = "listing_status", length = 40)
    private String listingStatus;

    /** 店铺（可空，如 PINKSIR），由「沃尔玛商品」扫描回写。 */
    @Column(length = 40)
    private String store;

    /** WFS 可售库存，来自领星 MCP 沃尔玛在线商品（每小时同步回写，替代文件扫描）。 */
    @Column(name = "wfs_available")
    private Integer wfsAvailable;

    /** 非 WFS（自发货）可售库存，来自领星 MCP。 */
    @Column(name = "non_wfs_available")
    private Integer nonWfsAvailable;

    /** Buy Box 价格（USD），来自领星 MCP。 */
    @Column(name = "buy_box_price", columnDefinition = "decimal(18,6)")
    private BigDecimal buyBoxPrice;

    /** 评论数，来自领星 MCP。 */
    @Column(name = "review_count")
    private Integer reviewCount;

    /** 平均评分，来自领星 MCP。 */
    @Column(name = "average_rating", columnDefinition = "decimal(6,2)")
    private BigDecimal averageRating;

    /** 竞品价格（USD），来自领星 MCP。 */
    @Column(name = "competitor_price", columnDefinition = "decimal(18,6)")
    private BigDecimal competitorPrice;

    /** 履约类型代码（1=WFS），来自领星 MCP。 */
    @Column(name = "fulfillment_type")
    private Integer fulfillmentType;

    /** 履约类型名称，来自领星 MCP。 */
    @Column(name = "fulfillment_type_name", length = 60)
    private String fulfillmentTypeName;

    /** 上架时间，来自领星 MCP。 */
    @Column(name = "listing_start_time", columnDefinition = "datetime")
    private LocalDateTime listingStartTime;

    /** 报价结束时间，来自领星 MCP。 */
    @Column(name = "offer_end_date", columnDefinition = "datetime")
    private LocalDateTime offerEndDate;

    /** 沃尔玛商品详情页 URL，来自领星 MCP。 */
    @Column(name = "item_url", length = 600)
    private String itemUrl;

    /** 促销明细（promotion_data_list JSON），来自领星 MCP。 */
    @Column(name = "promotion_json", columnDefinition = "json")
    private String promotionJson;

    /** 最近一次领星 MCP 同步时间。 */
    @Column(name = "last_synced_at", columnDefinition = "datetime")
    private LocalDateTime lastSyncedAt;

    /** 单件实际配送费（USD，可空），由 WFS 配送费报表 qty=1 标准行回填；利润计算兜底用。 */
    @Column(name = "delivery_fee", columnDefinition = "decimal(18,6)")
    private BigDecimal deliveryFee;

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

    public LocalDateTime getSourceUpdatedAt() {
        return sourceUpdatedAt;
    }

    public String getParentSku() {
        return parentSku;
    }

    public String getProductName() {
        return productName;
    }

    public String getBrand() {
        return brand;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
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

    public LocalDate getLaunchDate() {
        return launchDate;
    }

    public void setLaunchDate(LocalDate launchDate) {
        this.launchDate = launchDate;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getStrikethroughPrice() {
        return strikethroughPrice;
    }

    public void setStrikethroughPrice(BigDecimal strikethroughPrice) {
        this.strikethroughPrice = strikethroughPrice;
    }

    public String getUpc() {
        return upc;
    }

    public void setUpc(String upc) {
        this.upc = upc;
    }

    public String getGtin() {
        return gtin;
    }

    public void setGtin(String gtin) {
        this.gtin = gtin;
    }

    public String getListingStatus() {
        return listingStatus;
    }

    public void setListingStatus(String listingStatus) {
        this.listingStatus = listingStatus;
    }

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public Integer getWfsAvailable() {
        return wfsAvailable;
    }

    public void setWfsAvailable(Integer wfsAvailable) {
        this.wfsAvailable = wfsAvailable;
    }

    public Integer getNonWfsAvailable() {
        return nonWfsAvailable;
    }

    public void setNonWfsAvailable(Integer nonWfsAvailable) {
        this.nonWfsAvailable = nonWfsAvailable;
    }

    public BigDecimal getBuyBoxPrice() {
        return buyBoxPrice;
    }

    public void setBuyBoxPrice(BigDecimal buyBoxPrice) {
        this.buyBoxPrice = buyBoxPrice;
    }

    public boolean isCurrentVersion() {
        return currentVersion;
    }

    public Integer getReviewCount() {
        return reviewCount;
    }

    public void setReviewCount(Integer reviewCount) {
        this.reviewCount = reviewCount;
    }

    public BigDecimal getAverageRating() {
        return averageRating;
    }

    public void setAverageRating(BigDecimal averageRating) {
        this.averageRating = averageRating;
    }

    public BigDecimal getCompetitorPrice() {
        return competitorPrice;
    }

    public void setCompetitorPrice(BigDecimal competitorPrice) {
        this.competitorPrice = competitorPrice;
    }

    public Integer getFulfillmentType() {
        return fulfillmentType;
    }

    public void setFulfillmentType(Integer fulfillmentType) {
        this.fulfillmentType = fulfillmentType;
    }

    public String getFulfillmentTypeName() {
        return fulfillmentTypeName;
    }

    public void setFulfillmentTypeName(String fulfillmentTypeName) {
        this.fulfillmentTypeName = fulfillmentTypeName;
    }

    public LocalDateTime getListingStartTime() {
        return listingStartTime;
    }

    public void setListingStartTime(LocalDateTime listingStartTime) {
        this.listingStartTime = listingStartTime;
    }

    public LocalDateTime getOfferEndDate() {
        return offerEndDate;
    }

    public void setOfferEndDate(LocalDateTime offerEndDate) {
        this.offerEndDate = offerEndDate;
    }

    public String getItemUrl() {
        return itemUrl;
    }

    public void setItemUrl(String itemUrl) {
        this.itemUrl = itemUrl;
    }

    public String getPromotionJson() {
        return promotionJson;
    }

    public void setPromotionJson(String promotionJson) {
        this.promotionJson = promotionJson;
    }

    public LocalDateTime getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(LocalDateTime lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }

    public BigDecimal getDeliveryFee() {
        return deliveryFee;
    }

    public void setDeliveryFee(BigDecimal deliveryFee) {
        this.deliveryFee = deliveryFee;
    }
}
