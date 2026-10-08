package com.ai.walmartads.walmartad;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalmartAdSyncGuardTest {

    private static final WalmartAdSyncGuard.CoverageKey KEY =
            new WalmartAdSyncGuard.CoverageKey(
                    LocalDate.of(2026, 9, 16), 323561L, "SP", "REPORT:AD_ITEM");

    @Test
    void rejectsUnexpectedEmptyReplacement() {
        assertThatThrownBy(() -> WalmartAdSyncGuard.validate(Map.of(KEY, 2L), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stored data was preserved")
                .hasMessageContaining("fetched=0");
    }

    @Test
    void rejectsLargeCoverageDrop() {
        assertThatThrownBy(() -> WalmartAdSyncGuard.validate(Map.of(KEY, 1505L), Map.of(KEY, 1000L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("previous=1505")
                .hasMessageContaining("fetched=1000");
    }

    @Test
    void acceptsNormalFluctuationAndFirstImport() {
        assertThatCode(() -> WalmartAdSyncGuard.validate(Map.of(KEY, 1505L), Map.of(KEY, 1480L)))
                .doesNotThrowAnyException();
        assertThatCode(() -> WalmartAdSyncGuard.validate(Map.of(), Map.of(KEY, 1505L)))
                .doesNotThrowAnyException();
    }
}
