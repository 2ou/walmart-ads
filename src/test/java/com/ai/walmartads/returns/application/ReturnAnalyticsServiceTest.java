package com.ai.walmartads.returns.application;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReturnAnalyticsServiceTest {

    @Test
    void aggregatesDirectTotalsForTheImportedReportRange() {
        ReturnAnalyticsService.AccountSummary result =
                ReturnAnalyticsService.accountSummary(
                        List.of("2026-03", "2026-02"),
                        50,
                        25,
                        150,
                        350,
                        3_000
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
                ReturnAnalyticsService.accountSummary(List.of(), 0, 0, 0, 0, 0);

        assertThat(result.monthCount()).isZero();
        assertThat(result.firstMonth()).isNull();
        assertThat(result.lastMonth()).isNull();
        assertThat(result.completedRate()).isZero();
    }

    @Test
    void classifiesRiskFromCompletedRefundRateRatherThanRequestedUnits() {
        assertThat(ReturnAnalyticsService.refundRisk(7, 100)).isEqualTo("NORMAL");
        assertThat(ReturnAnalyticsService.refundRisk(8, 100)).isEqualTo("WATCH");
        assertThat(ReturnAnalyticsService.refundRisk(15, 100)).isEqualTo("HIGH");
        assertThat(ReturnAnalyticsService.refundRisk(1, 0)).isEqualTo("NO_SALES_DENOMINATOR");
    }

    @Test
    void parentRowsExposeCumulativeRiskSeparatelyFromMonthlyRisk() {
        assertThat(Arrays.stream(ReturnAnalyticsService.ParentRow.class.getRecordComponents())
                .map(RecordComponent::getName))
                .contains("risk", "totalRisk");
    }
}
