package com.ai.walmartads.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 渠道到达时效：渠道由物流表登记，时效天数全部由用户人工维护。 */
@Entity
@Table(name = "wm_ads_transit_channel_lead_time")
public class TransitChannelLeadTime extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

    @Id
    @Column(name = "channel_name", nullable = false, length = 300)
    private String channelName;

    @Column(name = "lead_days", precision = 10, scale = 2)
    private BigDecimal leadDays;

    @Column(name = "manual_override", nullable = false)
    private boolean manual;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected TransitChannelLeadTime() {
    }

    public TransitChannelLeadTime(String channelName, BigDecimal leadDays, boolean manual) {
        this.channelName = channelName;
        this.leadDays = leadDays;
        this.manual = manual;
        this.updatedAt = LocalDateTime.now();
    }

    public String getChannelName() { return channelName; }
    public BigDecimal getLeadDays() { return leadDays; }
    public boolean isManual() { return manual; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void updateManually(BigDecimal leadDays) {
        this.leadDays = leadDays;
        this.manual = true;
        this.updatedAt = LocalDateTime.now();
    }
}
