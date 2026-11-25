package com.ai.walmartads.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
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

    @Column(name = "reserved_units")
    private Integer reservedUnits;

    @Column(name = "inbound_units")
    private Integer inboundUnits;

    @Column(name = "suggested_units")
    private Integer suggestedUnits;

    @Column(name = "surplus_units")
    private Integer surplusUnits;

    @Column(name = "forecasted_days_supply", columnDefinition = "decimal(18,4)")
    private BigDecimal forecastedDaysSupply;

    @Column(name = "out_of_stock_date")
    private LocalDate outOfStockDate;

    @Column(name = "sell_through_rate", columnDefinition = "decimal(18,4)")
    private BigDecimal sellThroughRate;

    @Column(name = "item_lifecycle", length = 80)
    private String itemLifecycle;

    @Column(name = "onhand_units")
    private Integer onhandUnits;

    @Column(name = "unabled_warehousing_units")
    private Integer unabledWarehousingUnits;

    @Column(name = "inventory_review_units")
    private Integer inventoryReviewUnits;

    @Column(name = "damaged_units")
    private Integer damagedUnits;

    @Column(name = "ats_0_3m")
    private Integer ats03Months;

    @Column(name = "ats_3_6m")
    private Integer ats36Months;

    @Column(name = "ats_6_9m")
    private Integer ats69Months;

    @Column(name = "ats_9_12m")
    private Integer ats912Months;

    @Column(name = "ats_1y_plus")
    private Integer ats1YearPlus;

    @Column(name = "first_in_stock_date")
    private LocalDate firstInStockDate;

    @Column(name = "sales_forecast_w1_4", columnDefinition = "decimal(18,4)")
    private BigDecimal salesForecastW1To4;

    @Column(name = "sales_forecast_w5_8", columnDefinition = "decimal(18,4)")
    private BigDecimal salesForecastW5To8;

    @Column(name = "sales_forecast_w9_12", columnDefinition = "decimal(18,4)")
    private BigDecimal salesForecastW9To12;

    @Column(name = "wfs_stock_status", length = 80)
    private String wfsStockStatus;

    @Column(name = "customer_favorite", columnDefinition = "tinyint(1)")
    private Boolean customerFavorite;

    @Column(name = "purchase_cost", columnDefinition = "decimal(18,6)")
    private BigDecimal purchaseCost;

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

    public Integer getReservedUnits() { return reservedUnits; }
    public void setReservedUnits(Integer reservedUnits) { this.reservedUnits = reservedUnits; }
    public Integer getInboundUnits() { return inboundUnits; }
    public void setInboundUnits(Integer inboundUnits) { this.inboundUnits = inboundUnits; }
    public Integer getSuggestedUnits() { return suggestedUnits; }
    public void setSuggestedUnits(Integer suggestedUnits) { this.suggestedUnits = suggestedUnits; }
    public Integer getSurplusUnits() { return surplusUnits; }
    public void setSurplusUnits(Integer surplusUnits) { this.surplusUnits = surplusUnits; }
    public BigDecimal getForecastedDaysSupply() { return forecastedDaysSupply; }
    public void setForecastedDaysSupply(BigDecimal forecastedDaysSupply) { this.forecastedDaysSupply = forecastedDaysSupply; }
    public LocalDate getOutOfStockDate() { return outOfStockDate; }
    public void setOutOfStockDate(LocalDate outOfStockDate) { this.outOfStockDate = outOfStockDate; }
    public BigDecimal getSellThroughRate() { return sellThroughRate; }
    public void setSellThroughRate(BigDecimal sellThroughRate) { this.sellThroughRate = sellThroughRate; }
    public String getItemLifecycle() { return itemLifecycle; }
    public void setItemLifecycle(String itemLifecycle) { this.itemLifecycle = itemLifecycle; }
    public Integer getOnhandUnits() { return onhandUnits; }
    public void setOnhandUnits(Integer onhandUnits) { this.onhandUnits = onhandUnits; }
    public Integer getUnabledWarehousingUnits() { return unabledWarehousingUnits; }
    public void setUnabledWarehousingUnits(Integer unabledWarehousingUnits) { this.unabledWarehousingUnits = unabledWarehousingUnits; }
    public Integer getInventoryReviewUnits() { return inventoryReviewUnits; }
    public void setInventoryReviewUnits(Integer inventoryReviewUnits) { this.inventoryReviewUnits = inventoryReviewUnits; }
    public Integer getDamagedUnits() { return damagedUnits; }
    public void setDamagedUnits(Integer damagedUnits) { this.damagedUnits = damagedUnits; }
    public Integer getAts03Months() { return ats03Months; }
    public void setAts03Months(Integer ats03Months) { this.ats03Months = ats03Months; }
    public Integer getAts36Months() { return ats36Months; }
    public void setAts36Months(Integer ats36Months) { this.ats36Months = ats36Months; }
    public Integer getAts69Months() { return ats69Months; }
    public void setAts69Months(Integer ats69Months) { this.ats69Months = ats69Months; }
    public Integer getAts912Months() { return ats912Months; }
    public void setAts912Months(Integer ats912Months) { this.ats912Months = ats912Months; }
    public Integer getAts1YearPlus() { return ats1YearPlus; }
    public void setAts1YearPlus(Integer ats1YearPlus) { this.ats1YearPlus = ats1YearPlus; }
    public LocalDate getFirstInStockDate() { return firstInStockDate; }
    public void setFirstInStockDate(LocalDate firstInStockDate) { this.firstInStockDate = firstInStockDate; }
    public BigDecimal getSalesForecastW1To4() { return salesForecastW1To4; }
    public void setSalesForecastW1To4(BigDecimal salesForecastW1To4) { this.salesForecastW1To4 = salesForecastW1To4; }
    public BigDecimal getSalesForecastW5To8() { return salesForecastW5To8; }
    public void setSalesForecastW5To8(BigDecimal salesForecastW5To8) { this.salesForecastW5To8 = salesForecastW5To8; }
    public BigDecimal getSalesForecastW9To12() { return salesForecastW9To12; }
    public void setSalesForecastW9To12(BigDecimal salesForecastW9To12) { this.salesForecastW9To12 = salesForecastW9To12; }
    public String getWfsStockStatus() { return wfsStockStatus; }
    public void setWfsStockStatus(String wfsStockStatus) { this.wfsStockStatus = wfsStockStatus; }
    public Boolean getCustomerFavorite() { return customerFavorite; }
    public void setCustomerFavorite(Boolean customerFavorite) { this.customerFavorite = customerFavorite; }
    public BigDecimal getPurchaseCost() { return purchaseCost; }
    public void setPurchaseCost(BigDecimal purchaseCost) { this.purchaseCost = purchaseCost; }

    /** 当 wfs_stock_v2_list 偶发超时/限流时，继承当日上一版快照的 WFS 扩展明细。 */
    public void copyWfsDetailsFrom(LxInventorySnapshot other) {
        if (other == null) {
            return;
        }
        this.reservedUnits = other.reservedUnits;
        this.inboundUnits = other.inboundUnits;
        this.suggestedUnits = other.suggestedUnits;
        this.surplusUnits = other.surplusUnits;
        this.forecastedDaysSupply = other.forecastedDaysSupply;
        this.outOfStockDate = other.outOfStockDate;
        this.sellThroughRate = other.sellThroughRate;
        this.itemLifecycle = other.itemLifecycle;
        this.onhandUnits = other.onhandUnits;
        this.unabledWarehousingUnits = other.unabledWarehousingUnits;
        this.inventoryReviewUnits = other.inventoryReviewUnits;
        this.damagedUnits = other.damagedUnits;
        this.ats03Months = other.ats03Months;
        this.ats36Months = other.ats36Months;
        this.ats69Months = other.ats69Months;
        this.ats912Months = other.ats912Months;
        this.ats1YearPlus = other.ats1YearPlus;
        this.firstInStockDate = other.firstInStockDate;
        this.salesForecastW1To4 = other.salesForecastW1To4;
        this.salesForecastW5To8 = other.salesForecastW5To8;
        this.salesForecastW9To12 = other.salesForecastW9To12;
        this.wfsStockStatus = other.wfsStockStatus;
        this.customerFavorite = other.customerFavorite;
        if (this.purchaseCost == null) {
            this.purchaseCost = other.purchaseCost;
        }
    }
}
