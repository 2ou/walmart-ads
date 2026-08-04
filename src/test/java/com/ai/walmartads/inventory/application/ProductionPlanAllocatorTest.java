package com.ai.walmartads.inventory.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionPlanAllocatorTest {

    @Test
    void allocatesOneColorBedWithFivePieceMinimumButAllowsSinglePieceRemainder() {
        ProductionPlanAllocator.Allocation result = ProductionPlanAllocator.allocate(List.of(
                new ProductionPlanAllocator.Requirement("1X", 1),
                new ProductionPlanAllocator.Requirement("2X", 3),
                new ProductionPlanAllocator.Requirement("3X", 37)
        ), 45);

        assertThat(result.status()).isEqualTo(ProductionPlanAllocator.Status.ALLOCATED);
        assertThat(result.colorTotalUnits()).isEqualTo(45);
        assertThat(result.unitsByKey()).containsEntry("1X", 5d)
                .containsEntry("2X", 5d)
                .containsEntry("3X", 35d);
        assertThat(result.unitsByKey().values()).allMatch(value -> value >= 5d);
    }

    @Test
    void roundsAColorUpToCompleteBeds() {
        ProductionPlanAllocator.Allocation result = ProductionPlanAllocator.allocate(List.of(
                new ProductionPlanAllocator.Requirement("2X", 60),
                new ProductionPlanAllocator.Requirement("3X", 31)
        ), 45);

        assertThat(result.colorTotalUnits()).isEqualTo(135);
        assertThat(result.unitsByKey().values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(135d);
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
