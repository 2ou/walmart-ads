package com.ai.walmartads.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 货件级人工补充到达日期。
 *
 * <p>当物流登记表查不到渠道（算不出到达日），或系统推算的到达日已过期时，
 * 人工补录真实到达日，供路途货件明细分桶与倒计时使用。
 *
 * <p>刻意与渠道时效（{@link TransitChannelLeadTime}）分开：渠道时效是「渠道级」规则，
 * 这里是「货件级」一次性修正；且不参与需求预测的供应扣减。
 */
@Entity
@Table(name = "wm_ads_transit_shipment_eta")
public class TransitShipmentEta extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

    @Id
    @Column(name = "shipment_no", nullable = false, length = 120)
    private String shipmentNo;

    @Column(name = "arrival_date")
    private LocalDate arrivalDate;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected TransitShipmentEta() {
    }

    public TransitShipmentEta(String shipmentNo, LocalDate arrivalDate) {
        this.shipmentNo = shipmentNo;
        this.arrivalDate = arrivalDate;
        this.updatedAt = LocalDateTime.now();
    }

    public String getShipmentNo() { return shipmentNo; }
    public LocalDate getArrivalDate() { return arrivalDate; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    /** 传 null 表示清空人工值，回到系统推算。 */
    public void updateArrivalDate(LocalDate arrivalDate) {
        this.arrivalDate = arrivalDate;
        this.updatedAt = LocalDateTime.now();
    }
}
