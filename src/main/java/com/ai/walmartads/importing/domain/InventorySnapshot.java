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
@Table(name = "wm_ads_inventory_snapshot")
public class InventorySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_file_id", nullable = false)
    private ImportFile importFile;

    @Column(nullable = false)
    private LocalDateTime snapshotAt;

    @Column(nullable = false, length = 180)
    private String sku;

    @Column(length = 80)
    private String parentSku;

    @Column(length = 80)
    private String itemId;

    @Column(length = 40)
    private String gtin;

    @Column(columnDefinition = "text")
    private String itemName;

    @Column(length = 80)
    private String status;

    @Column(precision = 18, scale = 6)
    private BigDecimal dailySales;

    @Column(precision = 18, scale = 6)
    private BigDecimal dailyUnitsSold;

    @Column(precision = 18, scale = 4)
    private BigDecimal availableUnits;

    @Column(precision = 18, scale = 4)
    private BigDecimal reservedUnits;

    @Column(precision = 18, scale = 4)
    private BigDecimal inboundUnits;

    @Column(precision = 18, scale = 4)
    private BigDecimal forecastedDaysSupply;
    private LocalDate outOfStockDate;

    @Column(precision = 18, scale = 4)
    private BigDecimal suggestedUnits;

    @Column(length = 120)
    private String brand;

    @Column(precision = 18, scale = 4)
    private BigDecimal surplusUnits;

    @Column(precision = 18, scale = 8)
    private BigDecimal sellThroughRate;

    @Column(length = 80)
    private String publishingStatus;

    @Column(length = 80)
    private String itemLifecycle;

    @Column(nullable = false, columnDefinition = "json")
    private String rawPayload;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected InventorySnapshot() {
    }

    public InventorySnapshot(
            ImportFile importFile,
            LocalDateTime snapshotAt,
            String sku,
            String parentSku,
            String itemId,
            String gtin,
            String itemName,
            String status,
            BigDecimal dailySales,
            BigDecimal dailyUnitsSold,
            BigDecimal availableUnits,
            BigDecimal reservedUnits,
            BigDecimal inboundUnits,
            BigDecimal forecastedDaysSupply,
            LocalDate outOfStockDate,
            BigDecimal suggestedUnits,
            String brand,
            BigDecimal surplusUnits,
            BigDecimal sellThroughRate,
            String publishingStatus,
            String itemLifecycle,
            String rawPayload
    ) {
        this.importFile = importFile;
        this.snapshotAt = snapshotAt;
        this.sku = sku;
        this.parentSku = parentSku;
        this.itemId = itemId;
        this.gtin = gtin;
        this.itemName = itemName;
        this.status = status;
        this.dailySales = dailySales;
        this.dailyUnitsSold = dailyUnitsSold;
        this.availableUnits = availableUnits;
        this.reservedUnits = reservedUnits;
        this.inboundUnits = inboundUnits;
        this.forecastedDaysSupply = forecastedDaysSupply;
        this.outOfStockDate = outOfStockDate;
        this.suggestedUnits = suggestedUnits;
        this.brand = brand;
        this.surplusUnits = surplusUnits;
        this.sellThroughRate = sellThroughRate;
        this.publishingStatus = publishingStatus;
        this.itemLifecycle = itemLifecycle;
        this.rawPayload = rawPayload;
        this.createdAt = LocalDateTime.now();
    }

    public String getSku() {
        return sku;
    }

    public String getParentSku() {
        return parentSku;
    }
}
