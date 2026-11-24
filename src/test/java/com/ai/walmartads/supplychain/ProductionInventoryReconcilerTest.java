package com.ai.walmartads.supplychain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionInventoryReconcilerTest {
    private final ProductionSkuCanonicalizer canonicalizer = new ProductionSkuCanonicalizer(List.of("PNK-Blue-M-WFS02", "PNK-Red-L-WFS"));

    @Test
    void deductsOnlyPlannedShipmentsAndMatchesUniqueWfsSuffix() {
        var result = ProductionInventoryReconciler.reconcile(List.of(row("PO-1", "PNK-Blue-M-WFS", "100", "10")), List.of(
                delivery("PO-1", "PNK-Blue-M-WFS02", "1", "35"),
                delivery("PO-1", "PNK-Blue-M-WFS02", "1", "15"),
                delivery("PO-1", "PNK-Blue-M-WFS02", "0", "20"),
                delivery("PO-2", "PNK-Blue-M-WFS02", "1", "90")), canonicalizer);
        assertThat(result).singleElement().satisfies(row -> {
            assertThat(row.sku()).isEqualTo("PNK-Blue-M-WFS02");
            assertThat(row.plannedShipQty()).isEqualByComparingTo("50");
            assertThat(row.unplannedShipQty()).isEqualByComparingTo("20");
            assertThat(row.wipQty()).isEqualByComparingTo("50");
        });
    }

    @Test
    void allocatesShipmentOnlyOnceAcrossDuplicateOrderSkuRows() {
        var result = ProductionInventoryReconciler.reconcile(List.of(
                row("PO-1", "PNK-Red-L-WFS", "30", "30"),
                row("PO-1", "PNK-Red-L-WFS", "70", "70")),
                List.of(delivery("PO-1", "PNK-Red-L-WFS", "1", "40")), canonicalizer);
        assertThat(result.get(0).wipQty()).isEqualByComparingTo("0");
        assertThat(result.get(1).wipQty()).isEqualByComparingTo("60");
        assertThat(result.stream().map(CurrentProductionOrder::plannedShipQty).reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("40");
    }

    @Test
    void flagsExcessOrUnknownPlansAndNeverProducesNegativeInventory() {
        var excess = ProductionInventoryReconciler.reconcile(List.of(row("PO-1", "PNK-Red-L-WFS", "10", "10")),
                List.of(delivery("PO-1", "PNK-Red-L-WFS", "1", "15")), canonicalizer).getFirst();
        assertThat(excess.wipQty()).isEqualByComparingTo("0");
        assertThat(excess.reconciliationStatus()).isEqualTo("OVER_PLANNED");
        var unknown = ProductionInventoryReconciler.reconcile(List.of(row("PO-1", "PNK-Red-L-WFS", "10", "10")),
                List.of(delivery("PO-1", "PNK-Red-L-WFS", null, "5")), canonicalizer).getFirst();
        assertThat(unknown.wipQty()).isEqualByComparingTo("10");
        assertThat(unknown.reconciliationStatus()).isEqualTo("UNKNOWN_PLAN");
    }

    @Test
    void neverMatchesRowsWithMissingOrderNumber() {
        var result = ProductionInventoryReconciler.reconcile(List.of(row(null, "PNK-Red-L-WFS", "30", "7")),
                List.of(delivery(null, "PNK-Red-L-WFS", "1", "20")), canonicalizer).getFirst();
        assertThat(result.wipQty()).isEqualByComparingTo("7");
        assertThat(result.plannedShipQty()).isNull();
        assertThat(result.reconciliationStatus()).isEqualTo("UNMATCHABLE");
    }

    private static CurrentProductionOrder row(String order, String sku, String produced, String oldWip) {
        return new CurrentProductionOrder(order, "PNK", sku, null, null, new BigDecimal(produced),
                new BigDecimal(oldWip), null, "SUPPLY_CHAIN");
    }

    private static ProductionInventoryReconciler.Delivery delivery(String order, String sku, String flag, String qty) {
        return new ProductionInventoryReconciler.Delivery(order, sku, flag, new BigDecimal(qty));
    }
}
