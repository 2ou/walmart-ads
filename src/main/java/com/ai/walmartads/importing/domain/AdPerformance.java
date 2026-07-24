package com.ai.walmartads.importing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "wm_ads_ad_performance")
public class AdPerformance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_file_id", nullable = false)
    private ImportFile importFile;

    @Column(nullable = false)
    private LocalDate reportDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ImportFileType adType;

    @Column(nullable = false, length = 500)
    private String campaignName;

    @Column(length = 80)
    private String campaignId;

    @Column(length = 120)
    private String campaignType;

    @Column(length = 120)
    private String biddingStrategy;

    @Column(precision = 18, scale = 6)
    private BigDecimal targetRoas;

    @Column(length = 500)
    private String adGroupName;

    @Column(length = 80)
    private String adGroupId;

    @Column(length = 80)
    private String itemId;

    @Column(length = 180)
    private String sku;

    @Column(length = 80)
    private String parentSku;

    @Column(columnDefinition = "text")
    private String itemName;

    @Column(length = 1000)
    private String searchedKeyword;

    @Column(length = 1000)
    private String biddedKeyword;

    @Column(length = 80)
    private String matchType;

    @Column(precision = 18, scale = 6)
    private BigDecimal bid;

    @Column(precision = 18, scale = 6)
    private BigDecimal averageCpc;
    private long impressions;
    private long clicks;

    @Column(precision = 18, scale = 8)
    private BigDecimal ctr;

    @Column(precision = 18, scale = 6)
    private BigDecimal adSpend;

    @Column(precision = 18, scale = 4)
    private BigDecimal orders;

    @Column(precision = 18, scale = 8)
    private BigDecimal conversionRate;

    @Column(precision = 18, scale = 6)
    private BigDecimal attributedSales;

    @Column(precision = 18, scale = 6)
    private BigDecimal advertisedSkuSales;

    @Column(precision = 18, scale = 6)
    private BigDecimal otherSkuSales;

    @Column(precision = 18, scale = 4)
    private BigDecimal unitsSold;

    @Column(precision = 18, scale = 8)
    private BigDecimal roas;

    @Column(precision = 18, scale = 8)
    private BigDecimal omniRoas;

    @Column(nullable = false, columnDefinition = "json")
    private String rawPayload;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected AdPerformance() {
    }

    public ImportFileType getAdType() {
        return adType;
    }

    public String getCampaignType() {
        return campaignType;
    }

    public AdPerformance(
            ImportFile importFile,
            LocalDate reportDate,
            ImportFileType adType,
            String campaignName,
            String campaignId,
            String campaignType,
            String biddingStrategy,
            BigDecimal targetRoas,
            String adGroupName,
            String adGroupId,
            String itemId,
            String sku,
            String parentSku,
            String itemName,
            String searchedKeyword,
            String biddedKeyword,
            String matchType,
            BigDecimal bid,
            BigDecimal averageCpc,
            long impressions,
            long clicks,
            BigDecimal ctr,
            BigDecimal adSpend,
            BigDecimal orders,
            BigDecimal conversionRate,
            BigDecimal attributedSales,
            BigDecimal advertisedSkuSales,
            BigDecimal otherSkuSales,
            BigDecimal unitsSold,
            BigDecimal roas,
            BigDecimal omniRoas,
            String rawPayload
    ) {
        this.importFile = importFile;
        this.reportDate = reportDate;
        this.adType = adType;
        this.campaignName = campaignName;
        this.campaignId = campaignId;
        this.campaignType = campaignType;
        this.biddingStrategy = biddingStrategy;
        this.targetRoas = targetRoas;
        this.adGroupName = adGroupName;
        this.adGroupId = adGroupId;
        this.itemId = itemId;
        this.sku = sku;
        this.parentSku = parentSku;
        this.itemName = itemName;
        this.searchedKeyword = searchedKeyword;
        this.biddedKeyword = biddedKeyword;
        this.matchType = matchType;
        this.bid = bid;
        this.averageCpc = averageCpc;
        this.impressions = impressions;
        this.clicks = clicks;
        this.ctr = ctr;
        this.adSpend = adSpend;
        this.orders = orders;
        this.conversionRate = conversionRate;
        this.attributedSales = attributedSales;
        this.advertisedSkuSales = advertisedSkuSales;
        this.otherSkuSales = otherSkuSales;
        this.unitsSold = unitsSold;
        this.roas = roas;
        this.omniRoas = omniRoas;
        this.rawPayload = rawPayload;
        this.createdAt = LocalDateTime.now();
    }
}
