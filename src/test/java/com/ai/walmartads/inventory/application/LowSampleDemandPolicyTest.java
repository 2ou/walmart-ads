package com.ai.walmartads.inventory.application;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class LowSampleDemandPolicyTest {

    @Test
    void treatsExactlyThreeTrailing90UnitsAsLowSample() {
        assertThat(LowSampleDemandPolicy.requiresManualReview(3d)).isTrue();
        assertThat(LowSampleDemandPolicy.requiresManualReview(4d)).isFalse();
    }

    @Test
    void usesSameSeasonDemandAndItsSkuMixForLowSampleReference() {
        LowSampleDemandPolicy.Reference result = LowSampleDemandPolicy.reference(
                60,
                Map.of("recent-5X", 3d),
                Map.of("black-4X", 13d, "plaid-4X", 17d, "green-4X", 10d, "other", 31d)
        );

        assertThat(result.recentPaceDemand()).isEqualTo(2d);
        assertThat(result.sameSeasonDemand()).isEqualTo(71d);
        assertThat(result.totalDemand()).isEqualTo(71d);
        assertThat(result.allocatedDemandBySku()).containsEntry("black-4X", 13d)
                .containsEntry("plaid-4X", 17d)
                .containsEntry("green-4X", 10d)
                .containsEntry("other", 31d);
    }

    @Test
    void recoversDemandFromTheLastSellingWindowWhenRecentAndSeasonalSalesAreZero() {
        LowSampleDemandPolicy.Reference result = LowSampleDemandPolicy.reference(
                60,
                Map.of("black-L", 0d),
                Map.of("black-L", 0d),
                Map.of("black-L", 18d)
        );

        assertThat(result.lastSellingPaceDemand()).isEqualTo(12d);
        assertThat(result.totalDemand()).isEqualTo(12d);
        assertThat(result.allocatedDemandBySku()).containsEntry("black-L", 12d);
    }

    @Test
    void restoresOnlyOutOfStockSkusWithNoRecentOrdersToTheSizeCurve() {
        LowSampleDemandPolicy.AllocationWeights result = LowSampleDemandPolicy.recoverOutOfStockWeights(
                Map.of("black-L", 0d, "black-XL", 12d, "black-2X", 0d),
                Map.of("black-L", 9d, "black-XL", 30d, "black-2X", 6d),
                Set.of("black-L", "black-XL")
        );

        assertThat(result.weightsBySku())
                .containsEntry("black-L", 9d)
                .containsEntry("black-XL", 12d)
                .containsEntry("black-2X", 0d);
        assertThat(result.recoveredSkus()).containsExactly("black-L");
    }
}
