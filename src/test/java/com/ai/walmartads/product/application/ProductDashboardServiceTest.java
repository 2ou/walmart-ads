package com.ai.walmartads.product.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductDashboardServiceTest {

    @Test
    void usesTheOlderCompleteInventorySnapshotWhenTheLatestIsTruncated() {
        LocalDateTime latest = LocalDateTime.of(2026, 10, 24, 9, 12);
        LocalDateTime previous = LocalDateTime.of(2026, 10, 23, 23, 12);

        LocalDateTime selected = ProductDashboardService.selectCompleteSnapshot(List.of(
                new ProductDashboardService.SnapshotCandidate(latest, 200),
                new ProductDashboardService.SnapshotCandidate(previous, 1020)
        ));

        assertThat(selected).isEqualTo(previous);
    }

    @Test
    void alignsSalesAndAdsToTheirLatestComparableDate() {
        LocalDate selected = ProductDashboardService.comparableEnd(
                LocalDate.of(2026, 10, 23),
                LocalDate.of(2026, 10, 24),
                LocalDate.of(2026, 10, 27)
        );

        assertThat(selected).isEqualTo(LocalDate.of(2026, 10, 23));
    }

    @Test
    void fallsBackToTheAvailableBusinessSource() {
        LocalDate selected = ProductDashboardService.comparableEnd(
                LocalDate.of(2026, 10, 23),
                null,
                LocalDate.of(2026, 10, 27)
        );

        assertThat(selected).isEqualTo(LocalDate.of(2026, 10, 23));
    }

    @Test
    void keepsSmallAdvertisingRatesInsteadOfRoundingThemToZero() {
        assertThat(ProductDashboardService.rate(199, 193_959)).isEqualTo(0.001);
    }
}
