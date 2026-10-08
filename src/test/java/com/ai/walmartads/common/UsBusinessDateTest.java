package com.ai.walmartads.common;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class UsBusinessDateTest {

    @Test
    void keepsYesterdayVisibleBeforeFourPmBeijingTime() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-22T07:59:59Z"), ZoneOffset.UTC);

        assertThat(UsBusinessDate.visibleSalesDate(clock)).isEqualTo(LocalDate.of(2026, 7, 21));
    }

    @Test
    void makesTodayVisibleAtFourPmBeijingTime() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-22T08:00:00Z"), ZoneOffset.UTC);

        assertThat(UsBusinessDate.visibleSalesDate(clock)).isEqualTo(LocalDate.of(2026, 7, 22));
    }

    @Test
    void cutoffStaysFixedDuringLosAngelesWinterTime() {
        Clock clock = Clock.fixed(Instant.parse("2026-12-08T07:30:00Z"), ZoneOffset.UTC);

        assertThat(UsBusinessDate.visibleSalesDate(clock)).isEqualTo(LocalDate.of(2026, 12, 7));
    }
}
