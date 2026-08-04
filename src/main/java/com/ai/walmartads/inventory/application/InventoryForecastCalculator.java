package com.ai.walmartads.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class InventoryForecastCalculator {

    private InventoryForecastCalculator() {
    }

    public static Forecast calculate(
            double recentUnits,
            int sampleDays,
            double reportDailyUnits,
            double availableUnits,
            double transitUnits,
            double productionUnits,
            double surplusUnits,
            int targetDays
    ) {
        return calculate(
                recentUnits,
                sampleDays,
                reportDailyUnits,
                availableUnits,
                transitUnits,
                productionUnits,
                surplusUnits,
                targetDays,
                ForecastFactors.DEFAULT
        );
    }

    /**
     * 因子感知版：在速度法基础上叠加系统维护的输入因子。
     * 净日均需求 = 混合日均需求 × 季节性系数 × (1 − 退货率 × 退货重复利用率)
     * 有效覆盖天数 = 目标覆盖天数 + 增益天数 + 安全库存天数
     * 建议件数 = max(0, 净日均需求 × 有效覆盖天数 − 可用 − 在途 − 做货)
     * 任一因子缺省时自动退化为原速度法（seasonalCoef=1、其余=0）。
     */
    public static Forecast calculate(
            double recentUnits,
            int sampleDays,
            double reportDailyUnits,
            double availableUnits,
            double transitUnits,
            double productionUnits,
            double surplusUnits,
            int targetDays,
            ForecastFactors factors
    ) {
        int safeSampleDays = Math.max(sampleDays, 0);
        int safeTargetDays = Math.max(targetDays, 1);
        double recentDailyUnits = safeSampleDays == 0 ? 0 : nonNegative(recentUnits) / safeSampleDays;
        double reportDaily = nonNegative(reportDailyUnits);
        double demandDaily = blendedDailyDemand(recentDailyUnits, reportDaily, safeSampleDays);
        Double manualDailySales = factors.manualDailySales();
        if (manualDailySales != null && manualDailySales >= 0) {
            demandDaily = manualDailySales;
        }

        double seasonalCoef = factors.seasonalCoef() > 0 ? factors.seasonalCoef() : 1.0;
        double gainDays = Math.max(0, factors.gainDays());
        double safetyDays = Math.max(0, factors.safetyDays());
        double returnRate = clamp01(factors.returnRate());
        double returnReuseRate = clamp01(factors.returnReuseRate());
        double netDaily = demandDaily * seasonalCoef * (1 - returnRate * returnReuseRate);
        if (netDaily < 0) {
            netDaily = 0;
        }

        double available = nonNegative(availableUnits);
        double transit = nonNegative(transitUnits);
        double production = nonNegative(productionUnits);
        double totalSupply = available + transit + production;
        double coverDays = safeTargetDays + gainDays + safetyDays;
        double targetDemand = netDaily * coverDays;
        double forecastSuggested = Math.ceil(Math.max(0, targetDemand - totalSupply));
        Double projectedDaysSupply = netDaily > 0 ? round(totalSupply / netDaily) : null;
        String risk = risk(available, projectedDaysSupply, forecastSuggested, surplusUnits, safeTargetDays);
        String confidence = safeSampleDays >= 14 ? "HIGH" : safeSampleDays >= 7 ? "MEDIUM" : "LOW";
        return new Forecast(
                round(recentDailyUnits),
                round(netDaily),
                round(totalSupply),
                round(targetDemand),
                round(forecastSuggested),
                projectedDaysSupply,
                risk,
                confidence
        );
    }

    static double blendedDailyDemand(double recentDaily, double reportDaily, int sampleDays) {
        if (recentDaily <= 0) {
            return reportDaily;
        }
        if (reportDaily <= 0) {
            return recentDaily;
        }
        if (sampleDays >= 14) {
            return recentDaily * 0.75 + reportDaily * 0.25;
        }
        if (sampleDays >= 7) {
            return recentDaily * 0.60 + reportDaily * 0.40;
        }
        return recentDaily * 0.30 + reportDaily * 0.70;
    }

    private static String risk(
            double availableUnits,
            Double projectedDaysSupply,
            double forecastSuggested,
            double surplusUnits,
            int targetDays
    ) {
        if (availableUnits <= 0) {
            return "OUT_OF_STOCK";
        }
        if (projectedDaysSupply == null) {
            return "NO_DEMAND";
        }
        if (projectedDaysSupply <= 7) {
            return "URGENT";
        }
        if (projectedDaysSupply <= 14) {
            return "LOW";
        }
        if (projectedDaysSupply <= 30) {
            return "WATCH";
        }
        if (surplusUnits > 0 || projectedDaysSupply > targetDays * 1.5) {
            return "OVERSTOCK";
        }
        if (forecastSuggested > 0) {
            return "REPLENISH";
        }
        return "HEALTHY";
    }

    private static double nonNegative(double value) {
        return Math.max(value, 0);
    }

    private static double clamp01(double value) {
        if (value < 0) {
            return 0;
        }
        if (value > 1) {
            return 1;
        }
        return value;
    }

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * 补货预测输入因子。所有字段缺省（构造为 DEFAULT 时）表示「不使用该因子」。
     */
    public record ForecastFactors(
            double seasonalCoef,
            double gainDays,
            double safetyDays,
            double returnRate,
            double returnReuseRate,
            Double manualDailySales
    ) {
        public static final ForecastFactors DEFAULT = new ForecastFactors(1.0, 0, 0, 0, 0, null);
    }

    public record Forecast(
            double recentDailyUnits,
            double forecastDailyUnits,
            double totalSupply,
            double targetDemand,
            double forecastSuggestedUnits,
            Double projectedDaysSupply,
            String risk,
            String confidence
    ) {
    }
}
