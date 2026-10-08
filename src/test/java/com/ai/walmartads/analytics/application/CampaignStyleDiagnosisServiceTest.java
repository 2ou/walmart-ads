package com.ai.walmartads.analytics.application;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignStyleDiagnosisServiceTest {

    @Test
    void onlyLiveCampaignsParticipateInCurrentDiagnosis() {
        assertThat(CampaignStyleDiagnosisService.isCurrentCampaignStatus("投放中")).isTrue();
        assertThat(CampaignStyleDiagnosisService.isCurrentCampaignStatus("已暂停")).isFalse();
        assertThat(CampaignStyleDiagnosisService.isCurrentCampaignStatus("已结束")).isFalse();
        assertThat(CampaignStyleDiagnosisService.isCurrentCampaignStatus("提案")).isFalse();
        assertThat(CampaignStyleDiagnosisService.isCurrentCampaignStatus("状态未知")).isFalse();
    }

    @Test
    void derivesLifecycleRatesFromAggregatedBaseMetrics() {
        CampaignStyleDiagnosisService.HistoryMetrics metrics =
                CampaignStyleDiagnosisService.HistoryMetrics.fromValues(1_000, 100, 200, 20, 1_000);

        assertThat(metrics.ctr()).isEqualTo(0.1);
        assertThat(metrics.cpc()).isEqualTo(2.0);
        assertThat(metrics.cvr()).isEqualTo(0.2);
        assertThat(metrics.roas()).isEqualTo(5.0);
        assertThat(metrics.acos()).isEqualTo(0.2);
    }

    @Test
    void historySummaryCombinesCampaignsAndKeepsLifecycleBounds() {
        var first = historicalCampaign("1", LocalDate.of(2026, 1, 2), LocalDate.of(2026, 2, 3),
                CampaignStyleDiagnosisService.HistoryMetrics.fromValues(100, 10, 20, 2, 100));
        var second = historicalCampaign("2", LocalDate.of(2025, 12, 1), LocalDate.of(2026, 3, 4),
                CampaignStyleDiagnosisService.HistoryMetrics.fromValues(300, 30, 60, 6, 300));

        var summary = CampaignStyleDiagnosisService.HistorySummary.from(List.of(first, second));

        assertThat(summary.campaignCount()).isEqualTo(2);
        assertThat(summary.firstDate()).isEqualTo(LocalDate.of(2025, 12, 1));
        assertThat(summary.lastDate()).isEqualTo(LocalDate.of(2026, 3, 4));
        assertThat(summary.metrics().impressions()).isEqualTo(400);
        assertThat(summary.metrics().roas()).isEqualTo(5.0);
    }

    private static CampaignStyleDiagnosisService.HistoricalCampaign historicalCampaign(
            String campaignId,
            LocalDate firstDate,
            LocalDate lastDate,
            CampaignStyleDiagnosisService.HistoryMetrics metrics
    ) {
        return new CampaignStyleDiagnosisService.HistoricalCampaign(
                1, "SP", campaignId, "Campaign " + campaignId, "standard", "manual",
                "已暂停", "paused", 10, "fixed", firstDate, lastDate, metrics, List.of());
    }
}
