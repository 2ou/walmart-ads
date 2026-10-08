package com.ai.walmartads.analytics.application;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 广告决策阈值配置。
 *
 * 主键直接使用广告类型中文串（自动广告 / 手动广告），避免任何类型映射代码。
 * 阈值在此集中维护，前端通过接口读写，无需重启即可生效。
 */
@Entity
@Table(name = "wm_ads_campaign_decision_config")
public class CampaignDecisionConfig extends com.ai.walmartads.workspace.WorkspaceOwnedEntity {

    @Id
    @Column(name = "campaign_type", length = 20, nullable = false)
    private String campaignType;

    @Column(name = "min_clicks", nullable = false)
    private int minClicks;

    @Column(name = "min_spend", nullable = false, columnDefinition = "decimal(10, 2)")
    private double minSpend;

    @Column(name = "target_roas", nullable = false, columnDefinition = "decimal(6, 3)")
    private double targetRoas;

    @Column(name = "floor_roas", nullable = false, columnDefinition = "decimal(6, 3)")
    private double floorRoas;

    protected CampaignDecisionConfig() {
    }

    public CampaignDecisionConfig(String campaignType, int minClicks, double minSpend, double targetRoas, double floorRoas) {
        this.campaignType = campaignType;
        this.minClicks = minClicks;
        this.minSpend = minSpend;
        this.targetRoas = targetRoas;
        this.floorRoas = floorRoas;
    }

    public String getCampaignType() {
        return campaignType;
    }

    public void setCampaignType(String campaignType) {
        this.campaignType = campaignType;
    }

    public int getMinClicks() {
        return minClicks;
    }

    public void setMinClicks(int minClicks) {
        this.minClicks = minClicks;
    }

    public double getMinSpend() {
        return minSpend;
    }

    public void setMinSpend(double minSpend) {
        this.minSpend = minSpend;
    }

    public double getTargetRoas() {
        return targetRoas;
    }

    public void setTargetRoas(double targetRoas) {
        this.targetRoas = targetRoas;
    }

    public double getFloorRoas() {
        return floorRoas;
    }

    public void setFloorRoas(double floorRoas) {
        this.floorRoas = floorRoas;
    }
}
