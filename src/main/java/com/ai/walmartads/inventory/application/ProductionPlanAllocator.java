package com.ai.walmartads.inventory.application;

import java.time.LocalDate;
import java.util.ArrayList;
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

    private static final int MINIMUM_SKU_QUANTITY = 5;
    private static final double PARTIAL_BED_THRESHOLD = 0.5;

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
        Map<String, Integer> unitsByKey = new LinkedHashMap<>();
        positive.forEach(item -> unitsByKey.put(item.key(), 0));
        int remaining = colorTotal;

        // A size SKU that is produced receives at least five pieces.  If a single
        // bed cannot cover every size, this order is the agreed manual-review priority.
        for (Requirement item : positive) {
            if (remaining < MINIMUM_SKU_QUANTITY) {
                break;
            }
            int minimum = Math.min(MINIMUM_SKU_QUANTITY, remaining);
            unitsByKey.put(item.key(), minimum);
            remaining -= minimum;
        }

        // Before treating a bed's remainder as surplus, close every SKU's raw
        // 60-day shortage.  Extra units may be single pieces, not five-piece chunks.
        for (Requirement item : positive) {
            if (remaining == 0) {
                break;
            }
            int allocated = unitsByKey.get(item.key());
            int shortage = Math.max(0, (int) Math.ceil(item.rawSuggestedUnits()) - allocated);
            int applied = Math.min(remaining, shortage);
            unitsByKey.put(item.key(), allocated + applied);
            remaining -= applied;
        }

        distributeSurplus(positive, unitsByKey, remaining);

        Map<String, Double> allocations = new LinkedHashMap<>(emptyAllocations);
        unitsByKey.forEach((key, units) -> allocations.put(key, units.doubleValue()));
        Set<String> uncovered = new LinkedHashSet<>();
        positive.stream()
                .filter(item -> allocations.getOrDefault(item.key(), 0d) <= 0)
                .map(Requirement::key)
                .forEach(uncovered::add);
        return new Allocation(
                colorTotal,
                allocations,
                uncovered.isEmpty() ? Status.ALLOCATED : Status.PARTIALLY_COVERED,
                Set.copyOf(uncovered)
        );
    }

    private static void distributeSurplus(
            List<Requirement> requirements,
            Map<String, Integer> unitsByKey,
            int remaining
    ) {
        if (remaining <= 0) {
            return;
        }
        List<Requirement> eligible = requirements.stream()
                .filter(item -> item.trailing90Sales() > 0)
                .toList();
        if (eligible.isEmpty()) {
            return;
        }
        double weightTotal = eligible.stream().mapToDouble(Requirement::trailing90Sales).sum();
        Map<String, Double> remainders = new LinkedHashMap<>();
        int distributed = 0;
        for (Requirement item : eligible) {
            double exact = remaining * item.trailing90Sales() / weightTotal;
            int whole = (int) Math.floor(exact);
            unitsByKey.merge(item.key(), whole, Integer::sum);
            remainders.put(item.key(), exact - whole);
            distributed += whole;
        }
        List<Requirement> remainderOrder = new ArrayList<>(eligible);
        remainderOrder.sort(Comparator
                .comparingDouble((Requirement item) -> remainders.get(item.key())).reversed()
                .thenComparing(Requirement::trailing90Sales, Comparator.reverseOrder())
                .thenComparing(Requirement::key));
        for (int index = distributed; index < remaining; index++) {
            Requirement item = remainderOrder.get((index - distributed) % remainderOrder.size());
            unitsByKey.merge(item.key(), 1, Integer::sum);
        }
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
            LocalDate earliestStockoutDate,
            double trailing90Sales
    ) {
        public Requirement(String key, double rawSuggestedUnits) {
            this(key, rawSuggestedUnits, null, rawSuggestedUnits);
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
        MISSING_CUTTING_YIELD,
        MISSING_COLOR
    }
}
