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
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_file_id", nullable = false)
    private ImportFile importFile;

    @Column(nullable = false)
    private LocalDateTime sourceUpdatedAt;

    @Column(nullable = false)
    private boolean currentVersion;

    @Column(nullable = false, length = 180)
    private String sku;

    @Column(length = 80)
    private String parentSku;

    @Column(length = 160)
    private String productType;

    @Column(length = 80)
    private String productId;

    @Column(length = 40)
    private String productIdType;

    @Column(columnDefinition = "text")
    private String productName;

    @Column(length = 120)
    private String brand;

    @Column(columnDefinition = "mediumtext")
    private String shortDescription;

    @Column(columnDefinition = "json")
    private String keyFeatures;

    @Column(length = 2500)
    private String mainImageUrl;

    @Column(columnDefinition = "json")
    private String secondaryImageUrls;

    @Column(length = 600)
    private String color;

    @Column(length = 600)
    private String colorCategory;

    @Column(length = 120)
    private String size;

    @Column(length = 120)
    private String sizeGroup;

    @Column(length = 600)
    private String patternName;

    @Column(length = 4000)
    private String materialName;

    @Column(length = 80)
    private String gender;

    @Column(length = 300)
    private String variantGroupId;

    @Column(nullable = false, columnDefinition = "json")
    private String rawPayload;

    @Column(nullable = false)
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
}
