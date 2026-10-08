package com.ai.walmartads.analytics.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManualKeywordAnalysisServiceTest {

    private static final ManualKeywordAnalysisService.DecisionThresholds THRESHOLDS =
            new ManualKeywordAnalysisService.DecisionThresholds(5.0, 1.5, 20, 10.0);

    @Test
    void classifiesKeywordActionsFromManualThresholds() {
        assertThat(ManualKeywordAnalysisService.decide(20, 120, 4, 30, THRESHOLDS)).isEqualTo("scale");
        assertThat(ManualKeywordAnalysisService.decide(20, 20, 1, 30, THRESHOLDS)).isEqualTo("reduce");
        assertThat(ManualKeywordAnalysisService.decide(20, 60, 2, 30, THRESHOLDS)).isEqualTo("optimize");
        assertThat(ManualKeywordAnalysisService.decide(9, 100, 5, 30, THRESHOLDS)).isEqualTo("collect");
    }

    @Test
    void calculatesConservativeBidProposal() {
        assertThat(ManualKeywordAnalysisService.recommendedBid(0.30, "scale")).isEqualTo(0.33);
        assertThat(ManualKeywordAnalysisService.recommendedBid(0.30, "reduce")).isEqualTo(0.24);
        assertThat(ManualKeywordAnalysisService.recommendedBid(0.30, "collect")).isEqualTo(0.30);
    }
}
