package com.ai.walmartads.walmartad;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

public record AdSliceRow(
        LocalDate reportDate,
        long advertiserId,
        String adType,
        String sliceType,
        String entityKey,
        String campaignId,
        String campaignName,
        String campaignType,
        String campaignStatus,
        String targetingType,
        String adGroupId,
        String adGroupName,
        String dimensionValue,
        long impressions,
        long clicks,
        BigDecimal adSpend,
        BigDecimal attributedSales,
        BigDecimal attributedOrders,
        BigDecimal attributedUnits,
        BigDecimal advertisedSkuSales,
        BigDecimal advertisedSkuUnits,
        BigDecimal otherSkuSales,
        BigDecimal otherSkuUnits,
        BigDecimal ntbOrders,
        BigDecimal ntbRevenue,
        BigDecimal ntbUnits,
        BigDecimal cpc,
        BigDecimal ctr,
        BigDecimal cvr,
        BigDecimal acos,
        BigDecimal roas,
        BigDecimal aov,
        BigDecimal cpa,
        LocalDateTime syncedAt
) { }
