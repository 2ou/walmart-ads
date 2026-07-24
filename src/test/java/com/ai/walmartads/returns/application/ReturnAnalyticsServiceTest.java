package com.ai.walmartads.returns.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReturnAnalyticsServiceTest {

    @Test
    void aggregatesOnlyImportedMonthsUsingWeightedTotals() {
        List<ReturnAnalyticsService.MonthlyRow> trend = List.of(
                month("2026-02", 20, 10, 100, 200, 2_000),
                month("2026-03", 30, 15, 50, 150, 1_000),
                month("2026-07", 90, 80, 100, 900, 1_500)
        );

        ReturnAnalyticsService.AccountSummary result =
                ReturnAnalyticsService.accountSummary(
                        List.of("2026-03", "2026-02"),
                        trend
                );

        assertThat(result.firstMonth()).isEqualTo("2026-02");
        assertThat(result.lastMonth()).isEqualTo("2026-03");
        assertThat(result.monthCount()).isEqualTo(2);
        assertThat(result.requestedUnits()).isEqualTo(50);
        assertThat(result.completedUnits()).isEqualTo(25);
        assertThat(result.soldUnits()).isEqualTo(150);
        assertThat(result.requestRate()).isEqualTo(0.333333);
        assertThat(result.completedRate()).isEqualTo(0.166667);
        assertThat(result.refundAmount()).isEqualTo(350);
        assertThat(result.salesRevenue()).isEqualTo(3_000);
        assertThat(result.refundAmountRate()).isEqualTo(0.116667);
    }

    @Test
    void returnsZeroSummaryWhenNoImportedMonthsExist() {
        ReturnAnalyticsService.AccountSummary result =
                ReturnAnalyticsService.accountSummary(List.of(), List.of());

        assertThat(result.monthCount()).isZero();
        assertThat(result.firstMonth()).isNull();
        assertThat(result.lastMonth()).isNull();
        assertThat(result.completedRate()).isZero();
    }

    private static ReturnAnalyticsService.MonthlyRow month(
            String month,
            double requested,
            double completed,
            double sold,
            double refundAmount,
            double salesRevenue
    ) {
        return new ReturnAnalyticsService.MonthlyRow(
                month,
                requested,
                completed,
                0,
                0,
                sold,
                sold == 0 ? 0 : requested / sold,
                sold == 0 ? 0 : completed / sold,
                refundAmount,
                salesRevenue,
                salesRevenue == 0 ? 0 : refundAmount / salesRevenue
        );
    }
}
