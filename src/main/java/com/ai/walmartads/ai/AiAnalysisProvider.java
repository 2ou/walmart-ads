package com.ai.walmartads.ai;

import java.util.Optional;

public interface AiAnalysisProvider {

    Optional<AiAnalysisResult> analyze(AiAnalysisRequest request);

    record AiAnalysisRequest(String dataFingerprint, String metricsJson) {
    }

    record AiAnalysisResult(
            String provider,
            String model,
            String requestId,
            String analysisJson,
            int promptTokens,
            int completionTokens,
            int totalTokens
    ) {
    }
}
