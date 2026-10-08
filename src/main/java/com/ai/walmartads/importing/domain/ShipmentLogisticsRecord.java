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

/** 物流信息登记表的一条货件关联记录。 */
@Entity
@Table(name = "wm_ads_shipment_logistics")
public class ShipmentLogisticsRecord extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

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

    @Column(length = 120)
    private String forwarder;

    @Column(name = "channel_name", length = 300)
    private String channelName;

    @Column(name = "actual_departure_date")
    private LocalDate actualDepartureDate;

    @Column(name = "signed_date")
    private LocalDate signedDate;

    @Column(name = "signed_lead_days", precision = 10, scale = 2)
    private BigDecimal signedLeadDays;

    @Column(name = "raw_payload", nullable = false, columnDefinition = "json")
    private String rawPayload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    protected ShipmentLogisticsRecord() {
    }

    public ShipmentLogisticsRecord(
            ImportFile importFile,
            String shipmentNo,
            String forwarder,
            String channelName,
            LocalDate actualDepartureDate,
            LocalDate signedDate,
            BigDecimal signedLeadDays,
            String rawPayload
    ) {
        this.importFile = importFile;
        this.currentVersion = true;
        this.shipmentNo = shipmentNo;
        this.forwarder = forwarder;
        this.channelName = channelName;
        this.actualDepartureDate = actualDepartureDate;
        this.signedDate = signedDate;
        this.signedLeadDays = signedLeadDays;
        this.rawPayload = rawPayload;
        this.createdAt = LocalDateTime.now();
    }

    public String getShipmentNo() { return shipmentNo; }
    public String getForwarder() { return forwarder; }
    public String getChannelName() { return channelName; }
    public LocalDate getActualDepartureDate() { return actualDepartureDate; }
    public LocalDate getSignedDate() { return signedDate; }
    public BigDecimal getSignedLeadDays() { return signedLeadDays; }
}
