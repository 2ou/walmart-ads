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

    @Column(nullable = false, length = 80)
    private String parentSku;

    @Column(nullable = false, length = 80)
    private String itemId;

    @Column(nullable = false, length = 40)
    private String gtin;

    @Column(nullable = false, length = 40)
    private String upc;

    @Column(columnDefinition = "text")
    private String itemName;

    @Column(nullable = false, columnDefinition = "json")
    private String rawPayload;

    @Column(nullable = false)
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
