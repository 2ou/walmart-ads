package com.ai.walmartads.analytics.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardDataServiceDateTest {

    @Test
    void usesTheLastDateCoveredByOrdersAdsAndTheUsBusinessCalendar() {
        LocalDate result = DashboardDataService.comparableLatestDate(
                LocalDate.of(2026, 9, 29),
                LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 9, 29)
        );

        assertThat(result).isEqualTo(LocalDate.of(2026, 9, 29));
    }

    @Test
    void holdsTheDashboardBackWhenAdvertisingDataIsBehindOrders() {
        LocalDate result = DashboardDataService.comparableLatestDate(
                LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 9, 28),
                LocalDate.of(2026, 9, 30)
        );

        assertThat(result).isEqualTo(LocalDate.of(2026, 9, 28));
    }

    @Test
    void ignoresAMissingSourceDateInsteadOfDroppingTheDashboardDate() {
        LocalDate result = DashboardDataService.comparableLatestDate(
                LocalDate.of(2026, 9, 29),
                null,
                LocalDate.of(2026, 9, 29)
        );

        assertThat(result).isEqualTo(LocalDate.of(2026, 9, 29));
    }
}
