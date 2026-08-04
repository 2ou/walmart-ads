package com.ai.walmartads.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

@Entity
@Table(name = "wm_ads_parent_lifecycle")
public class ParentLifecycle {

    public static final String SEASONAL = "SEASONAL";
    public static final String SPRING_SUMMER = "SPRING_SUMMER";
    public static final String AUTUMN_WINTER = "AUTUMN_WINTER";
    public static final String ALL_SEASON = "ALL_SEASON";

    @Id
    @Column(name = "parent_sku", length = 180)
    private String parentSku;

    @Column(name = "replenishment_mode", nullable = false, length = 20)
    private String replenishmentMode;

    @Column(name = "season_type", nullable = false, length = 20)
    private String seasonType;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    protected ParentLifecycle() {
    }

    public ParentLifecycle(String parentSku) {
        this.parentSku = parentSku;
        this.replenishmentMode = "NORMAL";
        this.seasonType = SEASONAL;
        this.updatedAt = LocalDateTime.now();
    }

    public String getParentSku() {
        return parentSku;
    }

    public boolean isClearance() {
        return "CLEARANCE".equals(replenishmentMode);
    }

    public void setClearance(boolean clearance) {
        this.replenishmentMode = clearance ? "CLEARANCE" : "NORMAL";
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isAllSeason() {
        return ALL_SEASON.equals(seasonType);
    }

    public String getSeasonType() {
        return seasonType;
    }

    public void setSeasonType(String seasonType) {
        if (!isValidSeasonType(seasonType)) {
            throw new IllegalArgumentException("季节标签必须是春夏款、秋冬款、四季款或未标注季节。");
        }
        this.seasonType = seasonType;
        this.updatedAt = LocalDateTime.now();
    }

    public static boolean isValidSeasonType(String seasonType) {
        return SEASONAL.equals(seasonType)
                || SPRING_SUMMER.equals(seasonType)
                || AUTUMN_WINTER.equals(seasonType)
                || ALL_SEASON.equals(seasonType);
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
