package com.ai.walmartads.inventory.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LingxingSourcePolicyTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 20, 10, 0);

    @Test
    void explicitNewerUploadTakesOverImmediately() {
        assertThat(LingxingSourcePolicy.preferAutomatic(
                NOW.minusMinutes(30), NOW.minusMinutes(5), NOW)).isFalse();
    }

    @Test
    void newerUploadAlsoTakesOverWhenAutomaticSnapshotIsStale() {
        assertThat(LingxingSourcePolicy.preferAutomatic(
                NOW.minusHours(40), NOW.minusHours(1), NOW)).isFalse();
    }

    @Test
    void staleAutomaticSnapshotStillWinsOverAnOlderUpload() {
        assertThat(LingxingSourcePolicy.preferAutomatic(
                NOW.minusHours(40), NOW.minusDays(3), NOW)).isTrue();
    }

    @Test
    void automaticPresenceAndFreshnessBoundariesAreExplicit() {
        assertThat(LingxingSourcePolicy.preferAutomatic(null, NOW, NOW)).isFalse();
        assertThat(LingxingSourcePolicy.preferAutomatic(NOW.minusDays(1), null, NOW)).isTrue();
        assertThat(LingxingSourcePolicy.isAutomaticFresh(NOW.minusHours(36), NOW)).isTrue();
        assertThat(LingxingSourcePolicy.isAutomaticFresh(NOW.minusHours(36).minusNanos(1), NOW)).isFalse();
    }
}
