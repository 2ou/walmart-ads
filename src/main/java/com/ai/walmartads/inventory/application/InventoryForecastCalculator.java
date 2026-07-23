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
        int safeSampleDays = Math.max(sampleDays, 0);
        int safeTargetDays = Math.max(targetDays, 1);
        double recentDailyUnits = safeSampleDays == 0 ? 0 : nonNegative(recentUnits) / safeSampleDays;
        double reportDaily = nonNegative(reportDailyUnits);
        double demandDaily = blendedDailyDemand(recentDailyUnits, reportDaily, safeSampleDays);
        double available = nonNegative(availableUnits);
        double transit = nonNegative(transitUnits);
        double production = nonNegative(productionUnits);
        double totalSupply = available + transit + production;
        double targetDemand = demandDaily * safeTargetDays;
        double forecastSuggested = Math.ceil(Math.max(0, targetDemand - totalSupply));
        Double projectedDaysSupply = demandDaily > 0 ? round(totalSupply / demandDaily) : null;
        String risk = risk(available, projectedDaysSupply, forecastSuggested, surplusUnits, safeTargetDays);
        String confidence = safeSampleDays >= 14 ? "HIGH" : safeSampleDays >= 7 ? "MEDIUM" : "LOW";
        return new Forecast(
                round(recentDailyUnits),
                round(demandDaily),
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

    private static double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
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
