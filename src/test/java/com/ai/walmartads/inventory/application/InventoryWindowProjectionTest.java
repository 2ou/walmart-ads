package com.ai.walmartads.inventory.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InventoryWindowProjectionTest {

    @Test
    void numericSearchDoesNotMatchFragmentsOfOtherItemIds() {
        assertThat(InventoryDataService.inventorySearchMatches("6076", "PNK366076-C-Black", "PNK366076", "123456", "Nightgown")).isTrue();
        assertThat(InventoryDataService.inventorySearchMatches("6076", "PNK366077-FP-Spot Pink", "PNK366077", "20363356076", "Pajama Sets")).isFalse();
        assertThat(InventoryDataService.inventorySearchMatches("6076", "PNK366082-C-Dark Blue", "PNK366082", "19961360766", "Short Sets")).isFalse();
        assertThat(InventoryDataService.inventorySearchMatches("20363356076", "PNK366077-FP-Spot Pink", "PNK366077", "20363356076", "Pajama Sets")).isTrue();
    }

    @Test
    void textSearchMatchesSkuAndProductNameWithoutJoiningFields() {
        assertThat(InventoryDataService.inventorySearchMatches(" pnk366077 PAJAMA ", "PNK366077-FP-Spot Pink", "PNK366077", "1234", "Pajama Sets")).isTrue();
        assertThat(InventoryDataService.inventorySearchMatches("6078", "PNK36607", "8", "1234", "Pajama Sets")).isFalse();
    }

    @Test
    void styleRiskUsesAggregateSupplyInsteadOfARepresentativeSku() {
        assertThat(InventoryDataService.styleRisk(25, 70, 10, 0, 60)).isEqualTo("URGENT");
        assertThat(InventoryDataService.styleRisk(25, 140, 10, 0, 60)).isEqualTo("LOW");
        assertThat(InventoryDataService.styleRisk(25, 300, 10, 0, 60)).isEqualTo("WATCH");
    }

    @Test
    void styleRiskKeepsNoDemandAndOverstockSemantics() {
        assertThat(InventoryDataService.styleRisk(0, 100, 10, 50, 60)).isEqualTo("OUT_OF_STOCK");
        assertThat(InventoryDataService.styleRisk(20, 100, 0, 0, 60)).isEqualTo("NO_DEMAND");
        assertThat(InventoryDataService.styleRisk(20, 910, 10, 0, 60)).isEqualTo("OVERSTOCK");
        assertThat(InventoryDataService.styleRisk(20, 450, 10, 50, 60)).isEqualTo("REPLENISH");
    }
}
