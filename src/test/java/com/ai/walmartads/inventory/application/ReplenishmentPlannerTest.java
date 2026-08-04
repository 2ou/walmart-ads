package com.ai.walmartads.inventory.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplenishmentPlannerTest {

    @Test
    void usesDomesticStockBeforeFactoryProductionAndModelsItsThirtyDayArrival() {
        ReplenishmentPlanner.ParentPlan result = ReplenishmentPlanner.plan(new ReplenishmentPlanner.ParentInput(
                "PNK366066",
                LocalDate.of(2026, 8, 3),
                60,
                Collections.nCopies(60, 1d),
                Collections.nCopies(60, 1d),
                false,
                List.of(new ReplenishmentPlanner.VariantInput("PNK366066-C-Black-L-WFS", "Black", "L", 20, 90, 20, 100, 0, 0))
        ));

        ReplenishmentPlanner.VariantPlan plan = result.variants().getFirst();
        assertThat(plan.domesticShipmentUnits()).isEqualTo(40d);
        assertThat(plan.rawProductionUnits()).isZero();
        assertThat(plan.productionUnits()).isZero();
        assertThat(plan.expectedStockoutDate()).isEqualTo(LocalDate.of(2026, 8, 23));
    }

    @Test
    void requiresManualReviewWhenNewProductionCannotReachTheStockoutDate() {
        ReplenishmentPlanner.ParentPlan result = ReplenishmentPlanner.plan(new ReplenishmentPlanner.ParentInput(
                "PNK366066",
                LocalDate.of(2026, 8, 3),
                60,
                Collections.nCopies(60, 2d),
                Collections.nCopies(60, 2d),
                false,
                List.of(new ReplenishmentPlanner.VariantInput("PNK366066-C-Black-L-WFS", "Black", "L", 50, 180, 10, 30, 0, 0))
        ));

        ReplenishmentPlanner.VariantPlan plan = result.variants().getFirst();
        assertThat(plan.expectedStockoutDate()).isEqualTo(LocalDate.of(2026, 8, 8));
        assertThat(plan.domesticShipmentUnits()).isEqualTo(30d);
        assertThat(plan.rawProductionUnits()).isEqualTo(80d);
        assertThat(plan.productionUnits()).isZero();
        assertThat(plan.status()).isEqualTo(ReplenishmentPlanner.Status.MANUAL_REVIEW);
    }

    @Test
    void requiresManualReviewWhenAWholeBedWouldMoreThanDoubleTheRawShortage() {
        List<Double> demand = new java.util.ArrayList<>(Collections.nCopies(59, 0d));
        demand.add(10d);
        ReplenishmentPlanner.ParentPlan result = ReplenishmentPlanner.plan(new ReplenishmentPlanner.ParentInput(
                "PNK366066", LocalDate.of(2026, 8, 3), 60,
                demand, demand, false,
                List.of(new ReplenishmentPlanner.VariantInput("A", "Black", "L", 50, 90, 0, 0, 0, 0))
        ));

        ReplenishmentPlanner.VariantPlan plan = result.variants().getFirst();
        assertThat(plan.rawProductionUnits()).isEqualTo(10d);
        assertThat(plan.productionUnits()).isZero();
        assertThat(plan.status()).isEqualTo(ReplenishmentPlanner.Status.MANUAL_REVIEW);
    }

    @Test
    void recommendsWholeDomesticShipmentUnits() {
        ReplenishmentPlanner.ParentPlan result = ReplenishmentPlanner.plan(new ReplenishmentPlanner.ParentInput(
                "PNK366066", LocalDate.of(2026, 8, 3), 60,
                Collections.nCopies(60, .17d), Collections.nCopies(60, .17d), false,
                List.of(new ReplenishmentPlanner.VariantInput("A", "Black", "L", 20, 90, 0, 100, 0, 0))
        ));

        assertThat(result.variants().getFirst().domesticShipmentUnits()).isEqualTo(11d);
    }

    @Test
    void doesNotFlagStockoutWhenTheForecastHasNoDemand() {
        ReplenishmentPlanner.ParentPlan result = ReplenishmentPlanner.plan(new ReplenishmentPlanner.ParentInput(
                "PNK366066", LocalDate.of(2026, 8, 1), 60,
                Collections.nCopies(60, 0d), Collections.nCopies(60, 0d), false,
                List.of(new ReplenishmentPlanner.VariantInput("A", "Black", "L", 20, 90, 0, 0, 0, 0))
        ));

        ReplenishmentPlanner.VariantPlan plan = result.variants().getFirst();
        assertThat(plan.expectedStockoutDate()).isNull();
        assertThat(plan.highRiskStockoutDate()).isNull();
    }

    @Test
    void clearanceAndRetiredProductsDoNotShipOrProduce() {
        ReplenishmentPlanner.ParentPlan clearance = ReplenishmentPlanner.plan(new ReplenishmentPlanner.ParentInput(
                "PNK366066", LocalDate.of(2026, 8, 3), 60,
                Collections.nCopies(60, 1d), Collections.nCopies(60, 1d), true,
                List.of(new ReplenishmentPlanner.VariantInput("A", "Black", "L", 20, 90, 0, 100, 0, 0))
        ));
        ReplenishmentPlanner.ParentPlan retired = ReplenishmentPlanner.plan(new ReplenishmentPlanner.ParentInput(
                "PNK366066", LocalDate.of(2026, 8, 3), 60,
                Collections.nCopies(60, 1d), Collections.nCopies(60, 1d), false,
                List.of(new ReplenishmentPlanner.VariantInput("A", "Black", "L", 0, 90, 0, 100, 0, 0))
        ));

        assertThat(clearance.variants().getFirst().status()).isEqualTo(ReplenishmentPlanner.Status.CLEARANCE);
        assertThat(clearance.variants().getFirst().domesticShipmentUnits()).isZero();
        assertThat(retired.variants().getFirst().status()).isEqualTo(ReplenishmentPlanner.Status.RETIRED);
        assertThat(retired.variants().getFirst().productionUnits()).isZero();
    }

    @Test
    void usesTransitForCoverageWithoutReportingItAsFactoryWip() {
        ReplenishmentPlanner.ParentPlan result = ReplenishmentPlanner.plan(new ReplenishmentPlanner.ParentInput(
                "PNK363055", LocalDate.of(2026, 8, 3), 60,
                Collections.nCopies(60, 1d), Collections.nCopies(60, 1d), false,
                List.of(new ReplenishmentPlanner.VariantInput(
                        "PNK363055-Black-2X-WFS", "Black", "2X", 100, 90,
                        20, 0, 0, 0,
                        List.of(new ReplenishmentPlanner.ScheduledSupply(30, 40))
                ))
        ));

        ReplenishmentPlanner.VariantPlan plan = result.variants().getFirst();
        assertThat(plan.rawProductionUnits()).isZero();
        assertThat(plan.productionUnits()).isZero();
        assertThat(plan.wipUnits()).isZero();
        assertThat(plan.wipAvailableDay()).isZero();
    }
}
