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

/** 货件列表中的有效路途库存行。 */
@Entity
@Table(name = "wm_ads_shipment_record")
public class ShipmentRecord extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "import_file_id", nullable = false)
    private ImportFile importFile;

    @Column(name = "current_version", nullable = false)
    private boolean currentVersion;

    @Column(name = "shipment_no", nullable = false, length = 120)
    private String shipmentNo;

    @Column(name = "local_status", nullable = false, length = 80)
    private String localStatus;

    @Column(name = "shipment_created_at")
    private LocalDateTime shipmentCreatedAt;

    @Column(nullable = false, length = 180)
    private String sku;

    @Column(name = "parent_sku", length = 80)
    private String parentSku;

    @Column(name = "receipt_difference", nullable = false, precision = 18, scale = 4)
    private BigDecimal receiptDifference;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "json")
    private String rawPayload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ShipmentRecord() {
    }

    public ShipmentRecord(
            ImportFile importFile,
            String shipmentNo,
            String localStatus,
            LocalDateTime shipmentCreatedAt,
            String sku,
            String parentSku,
            BigDecimal receiptDifference,
            String rawPayload
    ) {
        this.importFile = importFile;
        this.currentVersion = true;
        this.shipmentNo = shipmentNo;
        this.localStatus = localStatus;
        this.shipmentCreatedAt = shipmentCreatedAt;
        this.sku = sku;
        this.parentSku = parentSku;
        this.receiptDifference = receiptDifference == null ? BigDecimal.ZERO : receiptDifference.max(BigDecimal.ZERO);
        this.rawPayload = rawPayload;
        this.createdAt = LocalDateTime.now();
    }

    public String getShipmentNo() { return shipmentNo; }
    public String getLocalStatus() { return localStatus; }
    public LocalDateTime getShipmentCreatedAt() { return shipmentCreatedAt; }
    public String getSku() { return sku; }
    public String getParentSku() { return parentSku; }
    public BigDecimal getReceiptDifference() { return receiptDifference; }
}
