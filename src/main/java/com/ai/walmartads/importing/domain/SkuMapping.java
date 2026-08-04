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
@Table(name = "wm_ads_sku_mapping")
public class SkuMapping {

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

    @Column(nullable = false, length = 80)
    /** 父 SKU */
    private String parentSku;

    @Column(nullable = false, length = 80)
    /** 商品ID */
    private String itemId;

    @Column(nullable = false, length = 40)
    /** GTIN */
    private String gtin;

    @Column(nullable = false, length = 40)
    /** UPC */
    private String upc;

    @Column(columnDefinition = "text")
    /** 商品名称（可空） */
    private String itemName;

    @Column(nullable = false, columnDefinition = "json")
    /** 原始报文（JSON） */
    private String rawPayload;

    @Column(nullable = false)
    /** 创建时间 */
    private LocalDateTime createdAt;

    protected SkuMapping() {
    }

    public SkuMapping(
            ImportFile importFile,
            LocalDateTime sourceUpdatedAt,
            String sku,
            String parentSku,
            String itemId,
            String gtin,
            String upc,
            String itemName,
            String rawPayload
    ) {
        this.importFile = importFile;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.currentVersion = true;
        this.sku = sku;
        this.parentSku = parentSku;
        this.itemId = itemId;
        this.gtin = gtin;
        this.upc = upc;
        this.itemName = itemName;
        this.rawPayload = rawPayload;
        this.createdAt = LocalDateTime.now();
    }

    public String getSku() {
        return sku;
    }

    public String getParentSku() {
        return parentSku;
    }

    public String getItemId() {
        return itemId;
    }

    public String getGtin() {
        return gtin;
    }

    public String getUpc() {
        return upc;
    }

    public String getItemName() {
        return itemName;
    }
}
