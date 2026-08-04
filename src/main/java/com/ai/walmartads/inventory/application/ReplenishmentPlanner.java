package com.ai.walmartads.inventory.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure planning logic for one parent style.  Forecasting is deliberately kept
 * outside this class so the inventory and manufacturing rules are testable.
 */
public final class ReplenishmentPlanner {

    public static final int DEFAULT_HORIZON_DAYS = 60;
    public static final int DOMESTIC_SHIPMENT_LEAD_DAYS = 30;
    public static final int FACTORY_TO_SELLABLE_DAYS = 60;
    private static final double MAX_AUTO_CUTTING_SURPLUS_RATIO = 1d;

    private ReplenishmentPlanner() {
    }

    public static ParentPlan plan(ParentInput input) {
        int horizon = Math.min(input.horizonDays(), Math.min(input.p50Daily().size(), input.p90Daily().size()));
        if (horizon <= 0) {
            return new ParentPlan(input.parentSku(), input.snapshotDate(), List.of(), 0, 0);
        }
        double totalTrailingSales = input.variants().stream()
                .mapToDouble(variant -> Math.max(0, variant.trailing90Sales()))
                .sum();
        List<MutablePlan> plans = new ArrayList<>();
        for (VariantInput variant : input.variants()) {
            double share = totalTrailingSales <= 0 ? 0 : Math.max(0, variant.trailing90Sales()) / totalTrailingSales;
            List<Double> p50 = allocatedDemand(input.p50Daily(), horizon, share);
            List<Double> p90 = allocatedDemand(input.p90Daily(), horizon, share);
            double demandP90 = p90.stream().mapToDouble(Double::doubleValue).sum();
            List<ScheduledSupply> scheduledSupplies = scheduledSupplies(variant, horizon);
            double scheduledSupplyUnits = scheduledSupplies.stream().mapToDouble(ScheduledSupply::units).sum();
            boolean retired = variant.cuttingYield() != null && variant.cuttingYield() == 0;
            boolean manualNoReplenish = input.clearance() || retired || share <= 0;
            double domesticShipment = manualNoReplenish
                    ? 0
                    : wholeShipment(
                            demandP90 - nonNegative(variant.wfsUnits()) - scheduledSupplyUnits,
                            variant.domesticUnits()
                    );
            double rawProduction = manualNoReplenish
                    ? 0
                    : Math.max(0, demandP90 - nonNegative(variant.wfsUnits()) - domesticShipment - scheduledSupplyUnits);
            LocalDate p50Stockout = stockoutDate(
                    input.snapshotDate(),
                    p50,
                    variant.wfsUnits(),
                    domesticShipment,
                    scheduledSupplies,
                    DOMESTIC_SHIPMENT_LEAD_DAYS
            );
            LocalDate p90Stockout = stockoutDate(
                    input.snapshotDate(),
                    p90,
                    variant.wfsUnits(),
                    domesticShipment,
                    scheduledSupplies,
                    DOMESTIC_SHIPMENT_LEAD_DAYS
            );
            plans.add(new MutablePlan(
                    variant,
                    demandP90,
                    domesticShipment,
                    rawProduction,
                    p50Stockout,
                    p90Stockout,
                    retired,
                    share <= 0,
                    input.clearance()
            ));
        }
        allocateProduction(plans, input.snapshotDate());
        List<VariantPlan> results = plans.stream()
                .sorted(Comparator.comparing(plan -> plan.input.sku()))
                .map(MutablePlan::toImmutable)
                .toList();
        return new ParentPlan(
                input.parentSku(),
                input.snapshotDate(),
                results,
                round(results.stream().mapToDouble(VariantPlan::domesticShipmentUnits).sum()),
                round(results.stream().mapToDouble(VariantPlan::productionUnits).sum())
        );
    }

    private static List<Double> allocatedDemand(List<Double> daily, int horizon, double share) {
        List<Double> result = new ArrayList<>(horizon);
        for (int index = 0; index < horizon; index++) {
            result.add(nonNegative(daily.get(index)) * share);
        }
        return result;
    }

    private static LocalDate stockoutDate(
            LocalDate snapshotDate,
            List<Double> dailyDemand,
            double wfsUnits,
            double domesticShipment,
            List<ScheduledSupply> scheduledSupplies,
            int domesticAvailableDay
    ) {
        if (dailyDemand.stream().mapToDouble(ReplenishmentPlanner::nonNegative).sum() <= 0) {
            return null;
        }
        double stock = nonNegative(wfsUnits);
        for (int day = 1; day <= dailyDemand.size(); day++) {
            int currentDay = day;
            if (day == domesticAvailableDay) {
                stock += nonNegative(domesticShipment);
            }
            stock += scheduledSupplies.stream()
                    .filter(supply -> supply.availableDay() == currentDay)
                    .mapToDouble(ScheduledSupply::units)
                    .sum();
            stock -= nonNegative(dailyDemand.get(day - 1));
            if (stock < 0.000001d) {
                return snapshotDate.plusDays(day);
            }
        }
        return null;
    }

    private static List<ScheduledSupply> scheduledSupplies(VariantInput variant, int horizon) {
        List<ScheduledSupply> result = new ArrayList<>();
        if (variant.wipAvailableDay() >= 1 && variant.wipAvailableDay() <= horizon && variant.wipUnits() > 0) {
            result.add(new ScheduledSupply(variant.wipAvailableDay(), variant.wipUnits()));
        }
        variant.additionalSupplies().stream()
                .filter(supply -> supply.availableDay() >= 1 && supply.availableDay() <= horizon && supply.units() > 0)
                .forEach(result::add);
        return result;
    }

    private static void allocateProduction(List<MutablePlan> plans, LocalDate snapshotDate) {
        Map<String, List<MutablePlan>> byColor = new LinkedHashMap<>();
        for (MutablePlan plan : plans) {
            if (plan.rawProduction <= 0 || plan.retired || plan.clearance || plan.noHistory) {
                continue;
            }
            if (plan.input.color() == null || plan.input.color().isBlank()) {
                plan.status = Status.MISSING_COLOR;
                continue;
            }
            if (plan.input.cuttingYield() == null) {
                plan.status = Status.MISSING_CUTTING_YIELD;
                continue;
            }
            if (plan.input.cuttingYield() == 0) {
                plan.status = Status.RETIRED;
                continue;
            }
            byColor.computeIfAbsent(plan.input.color().trim().toLowerCase(), ignored -> new ArrayList<>()).add(plan);
        }
        byColor.values().forEach(colorPlans -> {
            Integer cuttingYield = colorPlans.getFirst().input.cuttingYield();
            List<ProductionPlanAllocator.Requirement> requirements = colorPlans.stream()
                    .map(plan -> new ProductionPlanAllocator.Requirement(
                            plan.input.sku(),
                            plan.rawProduction,
                            plan.p50Stockout,
                            plan.input.trailing90Sales()
                    ))
                    .toList();
            ProductionPlanAllocator.Allocation allocation = ProductionPlanAllocator.allocate(requirements, cuttingYield);
            double rawProduction = colorPlans.stream().mapToDouble(plan -> plan.rawProduction).sum();
            double cuttingSurplus = Math.max(0, allocation.colorTotalUnits() - rawProduction);
            boolean stockoutBeforeNewProductionCanSell = colorPlans.stream().anyMatch(plan ->
                    plan.p90Stockout != null
                            && plan.p90Stockout.isBefore(snapshotDate.plusDays(FACTORY_TO_SELLABLE_DAYS))
            );
            boolean excessiveCuttingSurplus = rawProduction > 0
                    && cuttingSurplus > rawProduction * MAX_AUTO_CUTTING_SURPLUS_RATIO;
            if (stockoutBeforeNewProductionCanSell || excessiveCuttingSurplus) {
                // A new factory order cannot solve a stockout that happens before it can be sold.
                // Likewise, do not silently turn a small shortage into a disproportionately large bed.
                colorPlans.forEach(plan -> plan.status = Status.MANUAL_REVIEW);
                return;
            }
            colorPlans.forEach(plan -> {
                plan.colorProduction = allocation.colorTotalUnits();
                plan.production = allocation.unitsByKey().getOrDefault(plan.input.sku(), 0d);
                plan.status = allocation.uncoveredKeys().contains(plan.input.sku())
                        ? Status.BED_NOT_COVERED
                        : switch (allocation.status()) {
                            case ALLOCATED -> Status.PLANNED;
                            case PARTIALLY_COVERED -> Status.PARTIALLY_COVERED;
                            case MISSING_CUTTING_YIELD -> Status.MISSING_CUTTING_YIELD;
                            default -> Status.NOT_NEEDED;
                        };
            });
        });
        for (MutablePlan plan : plans) {
            if (plan.retired) {
                plan.status = Status.RETIRED;
            } else if (plan.clearance) {
                plan.status = Status.CLEARANCE;
            } else if (plan.noHistory) {
                plan.status = Status.NEW_NO_HISTORY;
            } else if (plan.rawProduction <= 0 && plan.status == null) {
                plan.status = Status.NOT_NEEDED;
            }
        }
    }

    private static double nonNegative(Double value) {
        return value == null ? 0 : Math.max(0, value);
    }

    private static double wholeShipment(double requiredUnits, double domesticAvailableUnits) {
        double availableWholeUnits = Math.floor(nonNegative(domesticAvailableUnits));
        double requiredWholeUnits = Math.ceil(nonNegative(requiredUnits));
        return Math.min(availableWholeUnits, requiredWholeUnits);
    }

    private static double round(double value) {
        return Math.round(value * 10000d) / 10000d;
    }

    public record ParentInput(
            String parentSku,
            LocalDate snapshotDate,
            int horizonDays,
            List<Double> p50Daily,
            List<Double> p90Daily,
            boolean clearance,
            List<VariantInput> variants
    ) {
        public ParentInput {
            snapshotDate = snapshotDate == null ? LocalDate.now() : snapshotDate;
            horizonDays = horizonDays <= 0 ? DEFAULT_HORIZON_DAYS : horizonDays;
            p50Daily = p50Daily == null ? List.of() : List.copyOf(p50Daily);
            p90Daily = p90Daily == null ? List.of() : List.copyOf(p90Daily);
            variants = variants == null ? List.of() : List.copyOf(variants);
        }
    }

    public record VariantInput(
            String sku,
            String color,
            String size,
            Integer cuttingYield,
            double trailing90Sales,
            double wfsUnits,
            double domesticUnits,
            double wipUnits,
            int wipAvailableDay,
            List<ScheduledSupply> additionalSupplies
    ) {
        public VariantInput {
            additionalSupplies = additionalSupplies == null ? List.of() : List.copyOf(additionalSupplies);
        }

        public VariantInput(
                String sku,
                String color,
                String size,
                Integer cuttingYield,
                double trailing90Sales,
                double wfsUnits,
                double domesticUnits,
                double wipUnits,
                int wipAvailableDay
        ) {
            this(sku, color, size, cuttingYield, trailing90Sales, wfsUnits, domesticUnits, wipUnits, wipAvailableDay, List.of());
        }
    }

    public record ScheduledSupply(int availableDay, double units) {
    }

    public record ParentPlan(
            String parentSku,
            LocalDate snapshotDate,
            List<VariantPlan> variants,
            double domesticShipmentUnits,
            double productionUnits
    ) {
    }

    public record VariantPlan(
            String sku,
            String color,
            String size,
            double demandP90Units,
            double wfsUnits,
            double domesticAvailableUnits,
            double domesticShipmentUnits,
            double wipUnits,
            int wipAvailableDay,
            double rawProductionUnits,
            double productionUnits,
            double colorProductionUnits,
            double cuttingSurplusUnits,
            LocalDate expectedStockoutDate,
            LocalDate highRiskStockoutDate,
            Status status
    ) {
    }

    public enum Status {
        PLANNED,
        PARTIALLY_COVERED,
        BED_NOT_COVERED,
        MANUAL_REVIEW,
        NOT_NEEDED,
        NEW_NO_HISTORY,
        CLEARANCE,
        RETIRED,
        MISSING_COLOR,
        MISSING_CUTTING_YIELD
    }

    private static final class MutablePlan {
        private final VariantInput input;
        private final double demandP90;
        private final double domesticShipment;
        private final double rawProduction;
        private final LocalDate p50Stockout;
        private final LocalDate p90Stockout;
        private final boolean retired;
        private final boolean noHistory;
        private final boolean clearance;
        private double production;
        private double colorProduction;
        private Status status;

        private MutablePlan(
                VariantInput input,
                double demandP90,
                double domesticShipment,
                double rawProduction,
                LocalDate p50Stockout,
                LocalDate p90Stockout,
                boolean retired,
                boolean noHistory,
                boolean clearance
        ) {
            this.input = input;
            this.demandP90 = demandP90;
            this.domesticShipment = domesticShipment;
            this.rawProduction = rawProduction;
            this.p50Stockout = p50Stockout;
            this.p90Stockout = p90Stockout;
            this.retired = retired;
            this.noHistory = noHistory;
            this.clearance = clearance;
        }

        private VariantPlan toImmutable() {
            return new VariantPlan(
                    input.sku(),
                    input.color(),
                    input.size(),
                    round(demandP90),
                    round(input.wfsUnits()),
                    round(input.domesticUnits()),
                    round(domesticShipment),
                    round(nonNegative(input.wipUnits())),
                    input.wipAvailableDay() >= 1 ? input.wipAvailableDay() : 0,
                    round(rawProduction),
                    round(production),
                    round(colorProduction),
                    round(Math.max(0, production - rawProduction)),
                    p50Stockout,
                    p90Stockout,
                    status == null ? Status.NOT_NEEDED : status
            );
        }
    }

}
