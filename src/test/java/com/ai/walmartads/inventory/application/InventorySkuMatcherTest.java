package com.ai.walmartads.inventory.application;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class InventorySkuMatcherTest {
    @Test
    void exactAndUniqueSuffixVariantsMatchAndUnknownRemainSeparate() {
        var matcher = new InventorySkuMatcher(List.of("PNK-A-WFS02", "PNK-B-WFS", "PNK-B-WFS02"));
        var result = matcher.align(Map.of("pnk-a-wfs", 3d, "PNK-B-WFS", 2d,
                "PNK-B-WFS03", 4d, "OTHER", 5d));
        assertThat(result.matched()).containsEntry("PNK-A-WFS02", 3d).containsEntry("PNK-B-WFS", 2d);
        assertThat(result.unmatched()).containsEntry("PNK-B-WFS03", 4d).containsEntry("OTHER", 5d);
    }

    @Test
    void productOnlySizesRemainVisibleAndUnmatchedWfsIsNotCounted() {
        var products = List.<Map<String, Object>>of(
                Map.of("sku", "PNK-A-WFS02", "parent_sku", "PNK", "size", "1X"),
                Map.of("sku", "PNK-B-WFS", "parent_sku", "PNK", "size", "2X"));
        var snapshot = List.<Map<String, Object>>of(
                Map.of("sku", "PNK-A-WFS", "available_units", 5),
                Map.of("sku", "UNKNOWN", "available_units", 8));
        var result = InventoryDataService.alignCatalog(products, snapshot,
                new InventorySkuMatcher(List.of("PNK-A-WFS02", "PNK-B-WFS")));
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().get(0)).containsEntry("available_units", 5d);
        assertThat(result.rows().get(1).get("sku")).isEqualTo("PNK-B-WFS");
        assertThat(result.rows().get(1).get("available_units")).isNull();
        assertThat(result.unmatchedWfs()).containsEntry("UNKNOWN", 8d);
    }
}
