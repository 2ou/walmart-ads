package com.ai.walmartads.inventory.application;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Prevents intermittent sales from being amplified into an automatic factory order.
 *
 * <p>When a parent has three or fewer trailing-90-day units, a single recent SKU
 * order is not a reliable size curve. The reference demand therefore uses the
 * greater of the same seasonal window last year, the observed recent pace, and
 * the last selling-window pace recovered for currently out-of-stock SKUs.</p>
 */
public final class LowSampleDemandPolicy {

    public static final double MAX_TRAILING_90_UNITS = 3d;
    private static final double TRAILING_WINDOW_DAYS = 90d;

    private LowSampleDemandPolicy() {
    }

    public static boolean requiresManualReview(double trailing90Units) {
        return Math.max(0, trailing90Units) <= MAX_TRAILING_90_UNITS;
    }

    public static Reference reference(
            int targetDays,
            Map<String, Double> trailing90BySku,
            Map<String, Double> sameSeasonLastYearBySku
    ) {
        return reference(targetDays, trailing90BySku, sameSeasonLastYearBySku, Map.of());
    }

    public static Reference reference(
            int targetDays,
            Map<String, Double> trailing90BySku,
            Map<String, Double> sameSeasonLastYearBySku,
            Map<String, Double> lastSellingPace90BySku
    ) {
        Map<String, Double> recent = nonNegativeCopy(trailing90BySku);
        Map<String, Double> seasonal = nonNegativeCopy(sameSeasonLastYearBySku);
        Map<String, Double> lastSelling = nonNegativeCopy(lastSellingPace90BySku);
        double recentTotal = total(recent);
        double seasonalTotal = total(seasonal);
        double recentPaceDemand = recentTotal * Math.max(1, targetDays) / TRAILING_WINDOW_DAYS;
        double lastSellingPaceDemand = total(lastSelling) * Math.max(1, targetDays) / TRAILING_WINDOW_DAYS;
        double referenceDemand = Math.max(seasonalTotal, Math.max(recentPaceDemand, lastSellingPaceDemand));

        Map<String, Double> recentForTarget = scale(recent, Math.max(1, targetDays) / TRAILING_WINDOW_DAYS);
        Map<String, Double> lastSellingForTarget = scale(lastSelling, Math.max(1, targetDays) / TRAILING_WINDOW_DAYS);
        Map<String, Double> allocationBasis;
        if (seasonalTotal >= recentPaceDemand && seasonalTotal >= lastSellingPaceDemand) {
            allocationBasis = new LinkedHashMap<>(seasonal);
        } else if (lastSellingPaceDemand >= recentPaceDemand) {
            allocationBasis = new LinkedHashMap<>(lastSellingForTarget);
        } else {
            allocationBasis = new LinkedHashMap<>(recentForTarget);
        }
        // Preserve a non-zero size curve for an out-of-stock SKU even when the
        // winning parent-level reference comes from another demand source.
        lastSellingForTarget.forEach((sku, units) -> allocationBasis.merge(sku, units, Math::max));
        return new Reference(
                referenceDemand,
                allocate(referenceDemand, allocationBasis),
                seasonalTotal,
                recentPaceDemand,
                lastSellingPaceDemand
        );
    }

    public static AllocationWeights recoverOutOfStockWeights(
            Map<String, Double> trailing90BySku,
            Map<String, Double> lastSellingPace90BySku,
            Set<String> outOfStockSkus
    ) {
        Map<String, Double> weights = nonNegativeCopy(trailing90BySku);
        Map<String, Double> lastSelling = nonNegativeCopy(lastSellingPace90BySku);
        Set<String> recoveredSkus = new LinkedHashSet<>();
        Set<String> safeOutOfStockSkus = outOfStockSkus == null ? Set.of() : outOfStockSkus;
        safeOutOfStockSkus.forEach(sku -> {
            double recentUnits = weights.getOrDefault(sku, 0d);
            double recoveredUnits = lastSelling.getOrDefault(sku, 0d);
            if (recentUnits <= 0 && recoveredUnits > 0) {
                weights.put(sku, recoveredUnits);
                recoveredSkus.add(sku);
            }
        });
        return new AllocationWeights(Map.copyOf(weights), Set.copyOf(recoveredSkus));
    }

    private static Map<String, Double> nonNegativeCopy(Map<String, Double> source) {
        Map<String, Double> result = new LinkedHashMap<>();
        if (source == null) {
            return result;
        }
        source.forEach((sku, units) -> {
            if (sku != null && !sku.isBlank()) {
                result.put(sku, Math.max(0, units == null ? 0 : units));
            }
        });
        return result;
    }

    private static Map<String, Double> allocate(double referenceDemand, Map<String, Double> basis) {
        Map<String, Double> result = new LinkedHashMap<>();
        double total = total(basis);
        basis.forEach((sku, units) -> result.put(sku, total <= 0 ? 0 : referenceDemand * units / total));
        return result;
    }

    private static Map<String, Double> scale(Map<String, Double> source, double factor) {
        Map<String, Double> result = new LinkedHashMap<>();
        source.forEach((sku, units) -> result.put(sku, Math.max(0, units) * Math.max(0, factor)));
        return result;
    }

    private static double total(Map<String, Double> values) {
        return values.values().stream().mapToDouble(value -> Math.max(0, value == null ? 0 : value)).sum();
    }

    public record Reference(
            double totalDemand,
            Map<String, Double> allocatedDemandBySku,
            double sameSeasonDemand,
            double recentPaceDemand,
            double lastSellingPaceDemand
    ) {
    }

    public record AllocationWeights(
            Map<String, Double> weightsBySku,
            Set<String> recoveredSkus
    ) {
    }
}
