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
    /** 主键，自增 */
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_file_id", nullable = false)
    /** 关联导入文件ID（wm_ads_import_file.id） */
    private ImportFile importFile;

    @Column(nullable = false)
    /** 报表日期 */
    private LocalDate reportDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    /** 广告类型（如 SP/SD 等） */
    private ImportFileType adType;

    @Column(nullable = false, length = 500)
    /** 广告活动名称 */
    private String campaignName;

    @Column(length = 80)
    /** 广告活动ID */
    private String campaignId;

    @Column(length = 120)
    /** 广告活动类型 */
    private String campaignType;

    @Column(length = 120)
    /** 出价策略 */
    private String biddingStrategy;

    @Column(precision = 18, scale = 6)
    /** 目标 ROAS（广告支出回报率） */
    private BigDecimal targetRoas;

    @Column(length = 500)
    /** 广告组名称 */
    private String adGroupName;

    @Column(length = 80)
    /** 广告组ID */
    private String adGroupId;

    @Column(length = 80)
    /** 商品ID（Walmart item id） */
    private String itemId;

    @Column(length = 180)
    /** 商品 SKU */
    private String sku;

    @Column(length = 80)
    /** 父 SKU */
    private String parentSku;

    @Column(columnDefinition = "text")
    /** 商品名称 */
    private String itemName;

    @Column(length = 1000)
    /** 搜索关键词 */
    private String searchedKeyword;

    @Column(length = 1000)
    /** 出价关键词 */
    private String biddedKeyword;

    @Column(length = 80)
    /** 匹配类型（精准/短语/广泛） */
    private String matchType;

    @Column(precision = 18, scale = 6)
    /** 出价 */
    private BigDecimal bid;

    @Column(precision = 18, scale = 6)
    /** 平均单次点击成本（CPC） */
    private BigDecimal averageCpc;
    /** 展示次数 */
    private long impressions;
    /** 点击次数 */
    private long clicks;

    @Column(precision = 18, scale = 8)
    /** 点击率（点击/展示） */
    private BigDecimal ctr;

    @Column(precision = 18, scale = 6)
    /** 广告花费 */
    private BigDecimal adSpend;

    @Column(precision = 18, scale = 4)
    /** 订单数 */
    private BigDecimal orders;

    @Column(precision = 18, scale = 8)
    /** 转化率（订单/点击） */
    private BigDecimal conversionRate;

    @Column(precision = 18, scale = 6)
    /** 归因销售额 */
    private BigDecimal attributedSales;

    @Column(precision = 18, scale = 6)
    /** 推广 SKU 销售额 */
    private BigDecimal advertisedSkuSales;

    @Column(precision = 18, scale = 6)
    /** 其他 SKU 销售额 */
    private BigDecimal otherSkuSales;

    @Column(precision = 18, scale = 4)
    /** 销售件数 */
    private BigDecimal unitsSold;

    @Column(precision = 18, scale = 8)
    /** 广告投资回报率（销售额/花费） */
    private BigDecimal roas;

    @Column(precision = 18, scale = 8)
    /** 全渠道 ROAS */
    private BigDecimal omniRoas;

    @Column(nullable = false, columnDefinition = "json")
    /** 原始报文（JSON，保留平台原始数据） */
    private String rawPayload;

    @Column(nullable = false)
    /** 记录创建时间 */
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
