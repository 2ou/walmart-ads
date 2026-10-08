package com.ai.walmartads.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "wm_ads_replenishment_decision")
public class ReplenishmentDecision extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

    @Id
    @Column(name = "parent_sku", nullable = false, length = 180)
    private String parentSku;

    @Column(name = "final_quantity", precision = 18, scale = 4)
    private BigDecimal finalQuantity;

    @Column(name = "system_suggested_quantity", precision = 18, scale = 4)
    private BigDecimal systemSuggestedQuantity;

    @Column(name = "demand_plan_quantity", precision = 18, scale = 4)
    private BigDecimal demandPlanQuantity;

    @Column(name = "target_days")
    private Integer targetDays;

    @Column(name = "decision_status", nullable = false, length = 20)
    private String status;

    @Column(name = "decision_note", length = 500)
    private String note;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ReplenishmentDecision() {
    }

    public ReplenishmentDecision(String parentSku) {
        this.parentSku = parentSku;
        this.status = "DRAFT";
        this.updatedAt = LocalDateTime.now();
    }

    public void update(
            BigDecimal finalQuantity,
            BigDecimal systemSuggestedQuantity,
            BigDecimal demandPlanQuantity,
            Integer targetDays,
            String status,
            String note
    ) {
        this.finalQuantity = finalQuantity;
        this.systemSuggestedQuantity = systemSuggestedQuantity;
        this.demandPlanQuantity = demandPlanQuantity;
        this.targetDays = targetDays;
        this.status = status;
        this.note = note;
        this.updatedAt = LocalDateTime.now();
    }

    public String getParentSku() { return parentSku; }
    public BigDecimal getFinalQuantity() { return finalQuantity; }
    public BigDecimal getSystemSuggestedQuantity() { return systemSuggestedQuantity; }
    public BigDecimal getDemandPlanQuantity() { return demandPlanQuantity; }
    public Integer getTargetDays() { return targetDays; }
    public String getStatus() { return status; }
    public String getNote() { return note; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
