package com.ai.walmartads.finance;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public final class FinanceModels {

    private FinanceModels() {
    }

    public record ParsedFinanceWorkbook(
            String fileName,
            String fileSha256,
            String templateEra,
            String fileReportMonth,
            String accountCode,
            List<StoreMonthlyRow> storeMonths,
            Map<String, List<StyleMonthlyRow>> styleMonths
    ) {
    }

    public record StoreMonthlyRow(
            String accountCode,
            String companyName,
            int reportYear,
            String reportMonth,
            String settlementPeriod,
            BigDecimal exchangeRate,
            BigDecimal salesRevenueUsd,
            BigDecimal refundUsd,
            BigDecimal commissionUsd,
            BigDecimal wfsFeeUsd,
            BigDecimal adSpendUsd,
            BigDecimal storageFeeUsd,
            BigDecimal inventoryReimbursementUsd,
            BigDecimal inventoryDisposalUsd,
            BigDecimal promoDiscountUsd,
            BigDecimal otherPlatformFeeUsd,
            BigDecimal netSettlementUsd,
            BigDecimal settlementRatio,
            BigDecimal netSettlementCny,
            BigDecimal purchaseCostCny,
            BigDecimal firstLegFreightCny,
            BigDecimal offsiteAdCny,
            BigDecimal offsiteFeeCny,
            BigDecimal grossProfitCny,
            BigDecimal profitMargin,
            int salesQty,
            int refundQty,
            int returnQty,
            Integer returnSellableQty,
            String sourceFileName
    ) {
    }

    public record StyleMonthlyRow(
            String accountCode,
            String companyName,
            String country,
            int reportYear,
            String reportMonth,
            String parentSku,
            String listingStatus,
            String season,
            String category,
            String ownerName,
            BigDecimal exchangeRate,
            BigDecimal avgSellingPriceUsd,
            int salesQty,
            BigDecimal salesRevenueUsd,
            int refundQty,
            BigDecimal refundUsd,
            BigDecimal refundRate,
            int returnQty,
            BigDecimal wfsFeeUsd,
            BigDecimal commissionUsd,
            BigDecimal promoDiscountUsd,
            BigDecimal adSpendUsd,
            BigDecimal storageFeeUsd,
            BigDecimal inventoryReimbursementUsd,
            BigDecimal inventoryDisposalUsd,
            BigDecimal otherPlatformFeeUsd,
            BigDecimal netSettlementUsd,
            BigDecimal netSettlementCny,
            BigDecimal purchaseCostCny,
            BigDecimal firstLegCostCny,
            BigDecimal offsiteFeeCny,
            BigDecimal profitCny,
            BigDecimal profitMargin,
            String sourceFileName
    ) {
    }
}
