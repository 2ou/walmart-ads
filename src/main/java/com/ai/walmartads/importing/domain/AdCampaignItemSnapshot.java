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
@Table(name = "_deprecated_wm_ads_ad_campaign_item_snapshot")
public class AdCampaignItemSnapshot extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

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

    @Column(nullable = false, length = 80)
    private String campaignId;

    @Column(length = 80)
    private String adGroupId;

    @Column(nullable = false, length = 80)
    private String itemId;

    @Column(length = 80)
    private String adItemId;

    @Column(nullable = false, columnDefinition = "decimal(18,6)")
    private BigDecimal bid;

    @Column(length = 40)
    private String itemStatus;

    @Column(columnDefinition = "text")
    private String itemName;

    @Column(length = 2500)
    private String itemImageUrl;

    @Column(length = 2500)
    private String itemPageUrl;

    @Column(length = 40)
    private String reviewStatus;

    @Column(columnDefinition = "text")
    private String reviewReason;

    @Column(nullable = false, columnDefinition = "json")
    private String rawPayload;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected AdCampaignItemSnapshot() {
    }

    public AdCampaignItemSnapshot(
            ImportFile importFile,
            LocalDateTime sourceUpdatedAt,
            String campaignId,
            String adGroupId,
            String itemId,
            String adItemId,
            BigDecimal bid,
            String itemStatus,
            String itemName,
            String itemImageUrl,
            String itemPageUrl,
            String reviewStatus,
            String reviewReason,
            String rawPayload
    ) {
        this.importFile = importFile;
        this.sourceUpdatedAt = sourceUpdatedAt;
        this.currentVersion = true;
        this.campaignId = campaignId;
        this.adGroupId = adGroupId;
        this.itemId = itemId;
        this.adItemId = adItemId;
        this.bid = bid == null ? BigDecimal.ZERO : bid;
        this.itemStatus = itemStatus;
        this.itemName = itemName;
        this.itemImageUrl = itemImageUrl;
        this.itemPageUrl = itemPageUrl;
        this.reviewStatus = reviewStatus;
        this.reviewReason = reviewReason;
        this.rawPayload = rawPayload;
        this.createdAt = LocalDateTime.now();
    }

    public String getCampaignId() {
        return campaignId;
    }

    public String getItemId() {
        return itemId;
    }

    public BigDecimal getBid() {
        return bid;
    }

    public String getItemStatus() {
        return itemStatus;
    }
}
