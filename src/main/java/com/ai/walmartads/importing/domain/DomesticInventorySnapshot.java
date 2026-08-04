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

/** Finished-goods stock in the domestic warehouse. */
@Entity
@Table(name = "wm_ads_domestic_inventory_snapshot")
public class DomesticInventorySnapshot {

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

    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal availableUnits;

    @Column(nullable = false, columnDefinition = "json")
    private String rawPayload;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    protected DomesticInventorySnapshot() {
    }

    public DomesticInventorySnapshot(
            ImportFile importFile,
            LocalDateTime snapshotAt,
            String sku,
            BigDecimal availableUnits,
            String rawPayload
    ) {
        this.importFile = importFile;
        this.snapshotAt = snapshotAt;
        this.sku = sku;
        this.availableUnits = availableUnits;
        this.rawPayload = rawPayload;
        this.createdAt = LocalDateTime.now();
    }

    public String getSku() {
        return sku;
    }

    public BigDecimal getAvailableUnits() {
        return availableUnits;
    }

    public LocalDateTime getSnapshotAt() {
        return snapshotAt;
    }
}
