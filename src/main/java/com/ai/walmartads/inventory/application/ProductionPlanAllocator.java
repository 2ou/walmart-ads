package com.ai.walmartads.inventory.application;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Converts a color's raw SKU shortages into a whole-bed production plan.
 */
public final class ProductionPlanAllocator {

    /** 工厂下单的每个 SKU 必须以 5 件为一个数量档位。 */
    private static final int SKU_QUANTITY_STEP = 5;
    private static final int MINIMUM_SKU_QUANTITY = SKU_QUANTITY_STEP;
    private static final double PARTIAL_BED_THRESHOLD = 0.5;
    private static final double CORE_SIZE_SALES_SHARE = 0.15;

    private ProductionPlanAllocator() {
    }

    public static Allocation allocate(List<Requirement> requirements, Integer cuttingYield) {
        List<Requirement> safeRequirements = requirements == null ? List.of() : requirements;
        Map<String, Double> emptyAllocations = new LinkedHashMap<>();
        safeRequirements.stream()
                .filter(item -> item != null && item.key() != null)
                .forEach(item -> emptyAllocations.put(item.key(), 0d));

        List<Requirement> positive = safeRequirements.stream()
                .filter(item -> item != null && item.key() != null && item.rawSuggestedUnits() > 0)
                .sorted(priorityOrder())
                .toList();
        if (positive.isEmpty()) {
            return new Allocation(0, emptyAllocations, Status.NOT_NEEDED, Set.of());
        }
        if (cuttingYield == null || cuttingYield <= 0) {
            return new Allocation(0, emptyAllocations, Status.MISSING_CUTTING_YIELD, Set.of());
        }
        // 若一床总数不是 5 的倍数，就无法同时满足「整床」与「每个 SKU 为 5 的倍数」。
        // 不输出 1~4 件尾数，交给人工修正裁床数后再计算。
        if (cuttingYield % SKU_QUANTITY_STEP != 0) {
            return new Allocation(0, emptyAllocations, Status.INCOMPATIBLE_CUTTING_YIELD, Set.of());
        }

        double rawTotal = positive.stream().mapToDouble(Requirement::rawSuggestedUnits).sum();
        // Whole beds are produced as before.  A cutting table only yields a full bed, so a
        // remainder that is too small to justify setting one up (below the threshold) is
        // deferred to a later cycle.  Once the remainder crosses the threshold it is worth
        // cutting, so a whole extra bed is added instead of forcing a half-cut bed.
        int fullBeds = (int) Math.floor(rawTotal / cuttingYield);
        double remainder = rawTotal - (double) fullBeds * cuttingYield;
        int partialUnits = remainder >= PARTIAL_BED_THRESHOLD * cuttingYield
                ? cuttingYield
                : 0;
        int colorTotal = fullBeds * cuttingYield + partialUnits;
        if (colorTotal <= 0) {
            return new Allocation(0, emptyAllocations, Status.DEFERRED_BELOW_BED_THRESHOLD, Set.of());
        }

        Map<String, Integer> maxUnitsByKey = new LinkedHashMap<>();
        long totalAutoCapacity = 0;
        for (Requirement item : positive) {
            int maxUnits = maximumPackUnits(item);
            maxUnitsByKey.put(item.key(), maxUnits);
            totalAutoCapacity += maxUnits;
        }
        if (colorTotal > totalAutoCapacity) {
            // A full colour bed would push one or more sizes beyond the automatic stock ceiling.
            // Defer the entire colour instead of hiding the excess in one high-selling size.
            return new Allocation(0, emptyAllocations, Status.DEFERRED_OVERSTOCK_RISK, Set.of());
        }

        Map<String, Integer> unitsByKey = new LinkedHashMap<>();
        positive.forEach(item -> unitsByKey.put(item.key(), 0));
        int remaining = colorTotal;
        Set<String> coreSizeKeys = coreSizeKeys(positive);

        // Protect only proven core sizes. Slow sizes still need a real shortage and are not
        // filled merely to make the colour look size-complete.
        for (Requirement item : positive.stream()
                .filter(candidate -> coreSizeKeys.contains(candidate.key()))
                .sorted(priorityOrder())
                .toList()) {
            if (remaining < MINIMUM_SKU_QUANTITY) {
                break;
            }
            if (maxUnitsByKey.getOrDefault(item.key(), 0) >= MINIMUM_SKU_QUANTITY) {
                unitsByKey.put(item.key(), MINIMUM_SKU_QUANTITY);
                remaining -= MINIMUM_SKU_QUANTITY;
            }
        }

        // Allocate the remaining real shortage one five-piece pack at a time. Earlier
        // stockouts win; core-size protection only breaks otherwise similar risks.
        while (remaining >= SKU_QUANTITY_STEP) {
            Requirement next = positive.stream()
                    .filter(item -> unitsByKey.get(item.key()) < roundUpToSkuPack(item.rawSuggestedUnits()))
                    .filter(item -> unitsByKey.get(item.key()) < maxUnitsByKey.get(item.key()))
                    .min(shortagePriority(unitsByKey, coreSizeKeys))
                    .orElse(null);
            if (next == null) {
                break;
            }
            unitsByKey.merge(next.key(), SKU_QUANTITY_STEP, Integer::sum);
            remaining -= SKU_QUANTITY_STEP;
        }

        // Whole-bed residue is a buffer, not another shortage. Spread it by the fewest
        // added cover-days first, and never cross the 90-day automatic stock ceiling.
        distributeBoundedBufferInSkuPacks(
                positive,
                unitsByKey,
                maxUnitsByKey,
                coreSizeKeys,
                remaining
        );

        Map<String, Double> allocations = new LinkedHashMap<>(emptyAllocations);
        unitsByKey.forEach((key, units) -> allocations.put(key, units.doubleValue()));
        Set<String> uncovered = new LinkedHashSet<>();
        positive.stream()
                .filter(item -> allocations.getOrDefault(item.key(), 0d) <= 0)
                .map(Requirement::key)
                .forEach(uncovered::add);
        boolean remainingShortfall = positive.stream().anyMatch(item ->
                allocations.getOrDefault(item.key(), 0d) + 0.000001d < item.rawSuggestedUnits()
        );
        return new Allocation(
                colorTotal,
                allocations,
                remainingShortfall ? Status.PARTIALLY_COVERED : Status.ALLOCATED,
                Set.copyOf(uncovered)
        );
    }

    private static void distributeBoundedBufferInSkuPacks(
            List<Requirement> requirements,
            Map<String, Integer> unitsByKey,
            Map<String, Integer> maxUnitsByKey,
            Set<String> coreSizeKeys,
            int remaining
    ) {
        int remainingPacks = remaining / SKU_QUANTITY_STEP;
        if (remainingPacks <= 0) {
            return;
        }
        for (int pack = 0; pack < remainingPacks; pack++) {
            Requirement next = requirements.stream()
                    .filter(item -> item.trailing90Sales() > 0)
                    .filter(item -> unitsByKey.get(item.key()) < maxUnitsByKey.get(item.key()))
                    .min(Comparator
                            .comparingDouble((Requirement item) -> addedCoverDays(item, unitsByKey.get(item.key())))
                            .thenComparing(Requirement::earliestStockoutDate,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(item -> !coreSizeKeys.contains(item.key()))
                            .thenComparing(Requirement::trailing90Sales, Comparator.reverseOrder())
                            .thenComparing(Requirement::key))
                    .orElse(null);
            if (next == null) {
                return;
            }
            unitsByKey.merge(next.key(), SKU_QUANTITY_STEP, Integer::sum);
        }
    }

    private static Comparator<Requirement> shortagePriority(
            Map<String, Integer> unitsByKey,
            Set<String> coreSizeKeys
    ) {
        return Comparator
                .comparing(Requirement::earliestStockoutDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(item -> !coreSizeKeys.contains(item.key()))
                .thenComparing(Comparator.comparingDouble((Requirement item) ->
                        Math.max(0, item.rawSuggestedUnits() - unitsByKey.get(item.key()))).reversed())
                .thenComparing(Requirement::trailing90Sales, Comparator.reverseOrder())
                .thenComparing(Requirement::key);
    }

    private static Set<String> coreSizeKeys(List<Requirement> requirements) {
        double salesTotal = requirements.stream().mapToDouble(Requirement::trailing90Sales).sum();
        if (salesTotal <= 0) {
            return Set.of();
        }
        Requirement topSeller = requirements.stream()
                .max(Comparator.comparingDouble(Requirement::trailing90Sales))
                .orElse(null);
        Set<String> result = new LinkedHashSet<>();
        requirements.stream()
                .filter(item -> item.trailing90Sales() / salesTotal >= CORE_SIZE_SALES_SHARE)
                .map(Requirement::key)
                .forEach(result::add);
        if (topSeller != null) {
            result.add(topSeller.key());
        }
        return Set.copyOf(result);
    }

    private static double addedCoverDays(Requirement item, int allocatedUnits) {
        double bufferUnits = Math.max(0, allocatedUnits - item.rawSuggestedUnits());
        double dailySales = item.trailing90Sales() / 90d;
        return dailySales <= 0 ? Double.POSITIVE_INFINITY : bufferUnits / dailySales;
    }

    private static int maximumPackUnits(Requirement item) {
        double safeMaximum = Math.max(item.rawSuggestedUnits(), item.maxAutoUnits());
        if (!Double.isFinite(safeMaximum)) {
            return Integer.MAX_VALUE - (Integer.MAX_VALUE % SKU_QUANTITY_STEP);
        }
        return roundUpToSkuPack(safeMaximum);
    }

    private static int roundUpToSkuPack(double units) {
        int wholeUnits = (int) Math.ceil(Math.max(0, units));
        return ((wholeUnits + SKU_QUANTITY_STEP - 1) / SKU_QUANTITY_STEP) * SKU_QUANTITY_STEP;
    }

    private static Comparator<Requirement> priorityOrder() {
        return Comparator
                .comparing(Requirement::earliestStockoutDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Requirement::rawSuggestedUnits, Comparator.reverseOrder())
                .thenComparing(Requirement::trailing90Sales, Comparator.reverseOrder())
                .thenComparing(Requirement::key);
    }

    public record Requirement(
            String key,
            double rawSuggestedUnits,
            double maxAutoUnits,
            LocalDate earliestStockoutDate,
            double trailing90Sales
    ) {
        public Requirement(String key, double rawSuggestedUnits, LocalDate earliestStockoutDate, double trailing90Sales) {
            this(key, rawSuggestedUnits, Double.POSITIVE_INFINITY, earliestStockoutDate, trailing90Sales);
        }

        public Requirement(String key, double rawSuggestedUnits) {
            this(key, rawSuggestedUnits, Double.POSITIVE_INFINITY, null, rawSuggestedUnits);
        }
    }

    public record Allocation(
            double colorTotalUnits,
            Map<String, Double> unitsByKey,
            Status status,
            Set<String> uncoveredKeys
    ) {
    }

    public enum Status {
        ALLOCATED,
        PARTIALLY_COVERED,
        NOT_NEEDED,
        DEFERRED_BELOW_BED_THRESHOLD,
        DEFERRED_OVERSTOCK_RISK,
        MISSING_CUTTING_YIELD,
        INCOMPATIBLE_CUTTING_YIELD,
        MISSING_COLOR
    }
}
