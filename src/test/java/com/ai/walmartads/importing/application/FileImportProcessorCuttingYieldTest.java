package com.ai.walmartads.importing.application;

import com.ai.walmartads.importing.domain.ProductRecord;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FileImportProcessorCuttingYieldTest {

    @Test
    void appliesDurableParentSettingToReplacementAndNewSku() {
        ProductRecord replacement = product("PNK100-Blue-XL-WFS", "PNK100");

        FileImportProcessor.applyCuttingYield(replacement, null, Map.of("PNK100", 85));

        assertThat(replacement.getCuttingYield()).isEqualTo(85);
    }

    @Test
    void fallsBackToPreviousSkuBeforeSettingMigration() {
        ProductRecord previous = product("PNK100-Blue-M-WFS", "PNK100");
        previous.setCuttingYield(70);
        ProductRecord replacement = product("PNK100-Blue-M-WFS", "PNK100");

        FileImportProcessor.applyCuttingYield(replacement, previous, Map.of());

        assertThat(replacement.getCuttingYield()).isEqualTo(70);
    }

    @Test
    void preservesZeroAsAnExplicitRetiredStyleSetting() {
        ProductRecord replacement = product("PNK100-Black-S-WFS", "PNK100");

        FileImportProcessor.applyCuttingYield(replacement, null, Map.of("PNK100", 0));

        assertThat(replacement.getCuttingYield()).isZero();
    }

    private static ProductRecord product(String sku, String parentSku) {
        return new ProductRecord(
                null, LocalDateTime.of(2026, 10, 10, 12, 0), sku, parentSku,
                null, null, null, null, null, null, "[]", null, "[]",
                null, null, null, null, null, null, null, null, "{}"
        );
    }
}
