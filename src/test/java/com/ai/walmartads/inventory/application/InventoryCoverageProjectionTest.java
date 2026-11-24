package com.ai.walmartads.inventory.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import static org.assertj.core.api.Assertions.assertThat;

class InventoryCoverageProjectionTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final LocalDate base = LocalDate.of(2026, 11, 6);

    @Test
    void arrivalAfterStockoutDoesNotHideTheFiveDayGap() throws Exception {
        var row = row(5).withReplenishmentPlan(plan(base.plusDays(5), 30), 60, base);
        assertThat(row.projectedDaysSupply()).isEqualTo(5d);
        assertThat(row.risk()).isEqualTo("URGENT");
        assertThat(row.coverageLowerBound()).isFalse();
    }

    @Test
    void wfsZeroRemainsOutOfStockEvenWhenDomesticWillArrive() throws Exception {
        var row = row(0).withReplenishmentPlan(plan(base.plusDays(30), 30), 60, base);
        assertThat(row.projectedDaysSupply()).isZero();
        assertThat(row.risk()).isEqualTo("OUT_OF_STOCK");
    }

    @Test
    void noStockoutWithinHorizonIsClearlyALowerBound() throws Exception {
        var row = row(50).withReplenishmentPlan(plan(null, 30), 60, base);
        assertThat(row.projectedDaysSupply()).isEqualTo(60d);
        assertThat(row.coverageLowerBound()).isTrue();
    }

    @Test
    void noDemandDoesNotInventCoverageOrThrow() throws Exception {
        var noDemand = new ReplenishmentPlanningService.VariantRecommendation("PNK", "PNK-A", "Black", "1X",
                "baseline", "HIGH", false, null, null, 0, 5, 100, 0,
                0, 0, 0, 0, 0, 0, null, null, "NOT_NEEDED");
        var row = row(5).withReplenishmentPlan(noDemand, 60, base);
        assertThat(row.projectedDaysSupply()).isNull();
        assertThat(row.risk()).isEqualTo("NO_DEMAND");
    }

    private InventoryDataService.InventoryRow row(int available) throws Exception {
        return mapper.readValue("{\"sku\":\"PNK-A\",\"parentSku\":\"PNK\",\"availableUnits\":" + available
                + ",\"forecastDailyUnits\":1,\"risk\":\"HEALTHY\"}", InventoryDataService.InventoryRow.class);
    }

    private ReplenishmentPlanningService.VariantRecommendation plan(LocalDate stockout, double domesticShipment) {
        return new ReplenishmentPlanningService.VariantRecommendation("PNK", "PNK-A", "Black", "1X",
                "baseline", "HIGH", false, null, null, 60, 5, 100, domesticShipment,
                0, 0, 0, 0, 0, 0, stockout, stockout, "NOT_NEEDED");
    }
}
