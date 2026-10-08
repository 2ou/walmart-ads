package com.ai.walmartads.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 领星在线商品库存快照（V49）。以领星为唯一库存源，替代 Walmart 后台 {@code inventory.xlsx} 报表。
 *
 * <p>按 {@code businessDate}（业务日）整窗替换写入：同一业务日内每小时同步只保留最后一版，
 * 跨天后该业务日那批行固化为历史。只承载领星真实能给、且下游在用的字段 ——
 * 原报表独有的 {@code reserved_units / sell_through_rate / item_lifecycle / daily_sales} 已下线。
 */
@Entity
@Table(name = "wm_ads_lx_inventory_snapshot")
public class LxInventorySnapshot extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    /** 主键，自增 */
    private Long id;

    @Column(name = "business_date", nullable = false)
    /** 业务日（整窗替换的键；同一天只保留最后一版） */
    private LocalDate businessDate;

    @Column(name = "snapshot_at", nullable = false)
    /** 该版本的同步时刻 */
    private LocalDateTime snapshotAt;

    @Column(nullable = false, length = 180)
    /** 商品 SKU */
    private String sku;

    @Column(name = "parent_sku", length = 80)
    /** 父 SKU（可空） */
    private String parentSku;

    @Column(name = "item_id", length = 80)
    /** 沃尔玛 Item Id（可空） */
    private String itemId;

    @Column(length = 40)
    /** GTIN（可空） */
    private String gtin;

    @Column(name = "item_name", columnDefinition = "text")
    /** 商品名称（可空） */
    private String itemName;

    @Column(length = 120)
    /** 品牌（可空） */
    private String brand;

    @Column(name = "listing_status", length = 80)
    /** 上架状态：PUBLISHED / UNPUBLISHED（可空） */
    private String listingStatus;

    @Column(name = "available_units", nullable = false)
    /** 可售库存 = wfs_available + non_wfs_available */
    private Integer availableUnits;

    @Column(name = "wfs_available")
    /** WFS 仓可售（领星 wfs_available_quantity） */
    private Integer wfsAvailable;

    @Column(name = "non_wfs_available")
    /** 非 WFS（卖家自发货）可售（领星 available_quantity） */
    private Integer nonWfsAvailable;

    @Column(name = "created_at", nullable = false)
    /** 创建时间 */
    private LocalDateTime createdAt;

    protected LxInventorySnapshot() {
    }

    public LxInventorySnapshot(LocalDate businessDate, LocalDateTime snapshotAt, String sku) {
        this.businessDate = businessDate;
        this.snapshotAt = snapshotAt;
        this.sku = sku;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public LocalDate getBusinessDate() {
        return businessDate;
    }

    public LocalDateTime getSnapshotAt() {
        return snapshotAt;
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

    public Integer getAvailableUnits() {
        return availableUnits;
    }

    public Integer getWfsAvailable() {
        return wfsAvailable;
    }

    public Integer getNonWfsAvailable() {
        return nonWfsAvailable;
    }

    public LxInventorySnapshot withParentSku(String parentSku) {
        this.parentSku = parentSku;
        return this;
    }

    public LxInventorySnapshot withItemId(String itemId) {
        this.itemId = itemId;
        return this;
    }

    public LxInventorySnapshot withGtin(String gtin) {
        this.gtin = gtin;
        return this;
    }

    public LxInventorySnapshot withItemName(String itemName) {
        this.itemName = itemName;
        return this;
    }

    public LxInventorySnapshot withBrand(String brand) {
        this.brand = brand;
        return this;
    }

    public LxInventorySnapshot withListingStatus(String listingStatus) {
        this.listingStatus = listingStatus;
        return this;
    }

    public LxInventorySnapshot withQuantities(Integer wfsAvailable, Integer nonWfsAvailable) {
        int wfs = wfsAvailable == null ? 0 : wfsAvailable;
        int nonWfs = nonWfsAvailable == null ? 0 : nonWfsAvailable;
        this.wfsAvailable = wfsAvailable;
        this.nonWfsAvailable = nonWfsAvailable;
        this.availableUnits = wfs + nonWfs;
        return this;
    }
}
