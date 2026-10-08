package com.ai.walmartads.walmartcomment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 领星 Walmart 店铺评价明细。业务键 (platform, comment_id)。
 *
 * <p>评价内容可被买家编辑，故同步走「按 comment_id upsert」而非按窗口整窗替换。
 * {@code parentSku} 在写入时由 {@code platformItemId} join {@code wm_ads_sku_mapping.item_id} 物化，
 * 未命中（历史下架款）留空，前端标记为「未归款」且不参与款级统计。
 */
@Entity
@Table(name = "wm_ads_lx_comment_raw")
public class WalmartComment extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 20)
    private String platform;

    @Column(nullable = false, length = 40)
    private String commentId;

    @Column(nullable = false, length = 40)
    private String storeId;

    @Column(length = 80)
    private String storeName;

    @Column(nullable = false, length = 40)
    private String platformItemId;

    @Column(length = 80)
    private String parentSku;

    @Column(nullable = false)
    private LocalDate commentDate;

    @Column(nullable = false)
    private int commentStar;

    @Column(length = 500)
    private String commentTitle;

    @Column(columnDefinition = "mediumtext")
    private String commentContent;

    @Column(length = 100)
    private String buyerName;

    @Column(columnDefinition = "text")
    private String productTitle;

    @Column(length = 1000)
    private String imageUrl;

    /** 领星原值，语义未在文档中明确，仅留档。 */
    private Integer buyerFlag;

    @Column(length = 20)
    private String siteCode;

    @Column(columnDefinition = "json")
    private String rawPayload;

    @Column(nullable = false)
    private LocalDateTime syncedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected WalmartComment() {
    }

    public WalmartComment(
            String platform,
            String commentId,
            String storeId,
            String storeName,
            String platformItemId,
            String parentSku,
            LocalDate commentDate,
            int commentStar,
            String commentTitle,
            String commentContent,
            String buyerName,
            String productTitle,
            String imageUrl,
            Integer buyerFlag,
            String siteCode,
            String rawPayload,
            LocalDateTime syncedAt,
            LocalDateTime createdAt
    ) {
        this.platform = platform;
        this.commentId = commentId;
        this.storeId = storeId;
        this.storeName = storeName;
        this.platformItemId = platformItemId;
        this.parentSku = parentSku;
        this.commentDate = commentDate;
        this.commentStar = commentStar;
        this.commentTitle = commentTitle;
        this.commentContent = commentContent;
        this.buyerName = buyerName;
        this.productTitle = productTitle;
        this.imageUrl = imageUrl;
        this.buyerFlag = buyerFlag;
        this.siteCode = siteCode;
        this.rawPayload = rawPayload;
        this.syncedAt = syncedAt;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getPlatform() {
        return platform;
    }

    public String getCommentId() {
        return commentId;
    }

    public String getStoreId() {
        return storeId;
    }

    public String getStoreName() {
        return storeName;
    }

    public String getPlatformItemId() {
        return platformItemId;
    }

    public String getParentSku() {
        return parentSku;
    }

    public LocalDate getCommentDate() {
        return commentDate;
    }

    public int getCommentStar() {
        return commentStar;
    }

    public String getCommentTitle() {
        return commentTitle;
    }

    public String getCommentContent() {
        return commentContent;
    }

    public String getBuyerName() {
        return buyerName;
    }

    public String getProductTitle() {
        return productTitle;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public Integer getBuyerFlag() {
        return buyerFlag;
    }

    public String getSiteCode() {
        return siteCode;
    }

    public String getRawPayload() {
        return rawPayload;
    }

    public LocalDateTime getSyncedAt() {
        return syncedAt;
    }

    public void setSyncedAt(LocalDateTime syncedAt) {
        this.syncedAt = syncedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    /** 是否为可归款的评价（未归款的评论不参与款级统计）。 */
    public boolean hasStyle() {
        return parentSku != null && !parentSku.isBlank();
    }

    // ---------------------------------------------------------------- upsert 用 setter

    public void setStoreName(String storeName) {
        this.storeName = storeName;
    }

    public void setParentSku(String parentSku) {
        this.parentSku = parentSku;
    }

    public void setCommentDate(LocalDate commentDate) {
        this.commentDate = commentDate;
    }

    public void setCommentStar(int commentStar) {
        this.commentStar = commentStar;
    }

    public void setCommentTitle(String commentTitle) {
        this.commentTitle = commentTitle;
    }

    public void setCommentContent(String commentContent) {
        this.commentContent = commentContent;
    }

    public void setBuyerName(String buyerName) {
        this.buyerName = buyerName;
    }

    public void setProductTitle(String productTitle) {
        this.productTitle = productTitle;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public void setBuyerFlag(Integer buyerFlag) {
        this.buyerFlag = buyerFlag;
    }

    public void setSiteCode(String siteCode) {
        this.siteCode = siteCode;
    }

    public void setRawPayload(String rawPayload) {
        this.rawPayload = rawPayload;
    }
}
