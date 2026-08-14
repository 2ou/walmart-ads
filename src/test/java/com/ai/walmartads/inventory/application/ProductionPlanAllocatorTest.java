package com.ai.walmartads.inventory.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionPlanAllocatorTest {

    @Test
    void roundsCrossThresholdRemainderUpToFullBed() {
        ProductionPlanAllocator.Allocation result = ProductionPlanAllocator.allocate(List.of(
                new ProductionPlanAllocator.Requirement("1X", 1),
                new ProductionPlanAllocator.Requirement("2X", 3),
                new ProductionPlanAllocator.Requirement("3X", 37)
        ), 45);

        // rawTotal 41 crosses the 50% (22.5) partial-bed threshold, so a whole extra bed
        // (45) is cut even though the unrounded remainder is 41.
        assertThat(result.status()).isEqualTo(ProductionPlanAllocator.Status.ALLOCATED);
        assertThat(result.colorTotalUnits()).isEqualTo(45);
        assertThat(result.unitsByKey()).containsEntry("1X", 5d)
                .containsEntry("2X", 5d)
                .containsEntry("3X", 35d);
        assertThat(result.unitsByKey().values()).allMatch(value -> value >= 5d);
    }

    @Test
    void producesFullBedsAndDefersSmallRemainder() {
        ProductionPlanAllocator.Allocation result = ProductionPlanAllocator.allocate(List.of(
                new ProductionPlanAllocator.Requirement("2X", 60),
                new ProductionPlanAllocator.Requirement("3X", 31)
        ), 45);

        // 91 = 2 full beds (90) + remainder 1, which is below the 50% threshold and deferred.
        assertThat(result.colorTotalUnits()).isEqualTo(90);
        assertThat(result.unitsByKey().values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(90d);
    }

    @Test
    void doesNotForceAWholeBedForASinglePieceNeed() {
        ProductionPlanAllocator.Allocation result = ProductionPlanAllocator.allocate(List.of(
                new ProductionPlanAllocator.Requirement("3X", 1)
        ), 10);

        // 1 piece is far below the 50% (5 piece) partial-bed threshold, so nothing is cut
        // this cycle and the need is deferred instead of forcing a 10-piece bed.
        assertThat(result.colorTotalUnits()).isZero();
        assertThat(result.status()).isEqualTo(ProductionPlanAllocator.Status.PARTIALLY_COVERED);
        assertThat(result.unitsByKey()).containsEntry("3X", 0d);
    }

    @Test
    void cutsAFullBedWhenRemainderReachesThreshold() {
        ProductionPlanAllocator.Allocation result = ProductionPlanAllocator.allocate(List.of(
                new ProductionPlanAllocator.Requirement("3X", 8)
        ), 10);

        // 8 pieces >= 50% of a 10-piece bed, so a whole extra bed (10) is cut rather than a
        // half-bed of 8; a cutting table only yields full beds.
        assertThat(result.colorTotalUnits()).isEqualTo(10);
        assertThat(result.unitsByKey()).containsEntry("3X", 10d);
        assertThat(result.status()).isEqualTo(ProductionPlanAllocator.Status.ALLOCATED);
    }

    @Test
    void preservesFullBedQuantitiesForLargeNeeds() {
        ProductionPlanAllocator.Allocation result = ProductionPlanAllocator.allocate(List.of(
                new ProductionPlanAllocator.Requirement("2X", 55),
                new ProductionPlanAllocator.Requirement("3X", 45)
        ), 10);

        // 100 pieces is an exact multiple of the bed size, so no rounding or deferral.
        assertThat(result.colorTotalUnits()).isEqualTo(100);
        assertThat(result.unitsByKey().values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(100d);
    }

    @Test
    void onlyRejectsMissingCuttingYieldBecauseBedQuantityNeedNotBeAMultipleOfFive() {
        List<ProductionPlanAllocator.Requirement> requirements = List.of(
                new ProductionPlanAllocator.Requirement("3X", 24)
        );

        assertThat(ProductionPlanAllocator.allocate(requirements, null).status())
                .isEqualTo(ProductionPlanAllocator.Status.MISSING_CUTTING_YIELD);
        assertThat(ProductionPlanAllocator.allocate(requirements, 1).status())
                .isEqualTo(ProductionPlanAllocator.Status.ALLOCATED);
    }

    @Test
    void returnsNoPlanWhenThereIsNoRawShortfall() {
        ProductionPlanAllocator.Allocation result = ProductionPlanAllocator.allocate(List.of(
                new ProductionPlanAllocator.Requirement("1X", 0),
                new ProductionPlanAllocator.Requirement("2X", 0)
        ), 45);

        assertThat(result.status()).isEqualTo(ProductionPlanAllocator.Status.NOT_NEEDED);
        assertThat(result.colorTotalUnits()).isZero();
    }
}
