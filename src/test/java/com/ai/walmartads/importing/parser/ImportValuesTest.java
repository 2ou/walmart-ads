package com.ai.walmartads.importing.parser;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ImportValuesTest {

    @Test
    void normalizesMoneyPercentDateAndParentSku() {
        assertThat(ImportValues.decimal("$1,234.56")).isEqualByComparingTo("1234.56");
        assertThat(ImportValues.decimal("11.11%")).isEqualByComparingTo(new BigDecimal("0.1111000000"));
        assertThat(ImportValues.date("7/21/2026")).isEqualTo(LocalDate.of(2026, 7, 21));
        assertThat(ImportValues.date("2026-07-15 20:02:06")).isEqualTo(LocalDate.of(2026, 7, 15));
        assertThat(ImportValues.parentSku("PNK363127-C-Steel Gray-3X-WFS")).isEqualTo("PNK363127");
    }

    @Test
    void preservesRepeatedTemplateFieldsAsLists() {
        Map<String, Object> payload = new LinkedHashMap<>();

        ImportValues.addPreservingDuplicates(payload, "keyFeatures", "feature one");
        ImportValues.addPreservingDuplicates(payload, "keyFeatures", "feature two");

        assertThat(ImportValues.list(payload, "keyFeatures"))
                .containsExactly("feature one", "feature two");
    }
}
