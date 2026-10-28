package com.ai.walmartads.inventory.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InventorySnapshotCompletenessTest {

    @Test
    void rejectsAOnePageReplacementForACompleteSnapshot() {
        assertThat(LxInventorySnapshotWriter.isSuspiciouslySmall(200, 1020)).isTrue();
    }

    @Test
    void acceptsANormalSizedReplacement() {
        assertThat(LxInventorySnapshotWriter.isSuspiciouslySmall(970, 1020)).isFalse();
    }

    @Test
    void inventoryOverviewFallsBackFromTruncatedLatestSnapshot() {
        LocalDateTime latest = LocalDateTime.of(2026, 10, 24, 9, 12);
        LocalDateTime previous = LocalDateTime.of(2026, 10, 23, 23, 12);

        LocalDateTime selected = InventoryDataService.selectCompleteSnapshot(List.of(
                new InventoryDataService.SnapshotCandidate(latest, 200),
                new InventoryDataService.SnapshotCandidate(previous, 1020)
        ));

        assertThat(selected).isEqualTo(previous);
    }

    @Test
    void inventoryOverviewKeepsLatestWhenItsSizeIsPlausible() {
        LocalDateTime latest = LocalDateTime.of(2026, 10, 24, 9, 12);
        LocalDateTime previous = LocalDateTime.of(2026, 10, 23, 23, 12);

        LocalDateTime selected = InventoryDataService.selectCompleteSnapshot(List.of(
                new InventoryDataService.SnapshotCandidate(latest, 980),
                new InventoryDataService.SnapshotCandidate(previous, 1020)
        ));

        assertThat(selected).isEqualTo(latest);
    }
}
