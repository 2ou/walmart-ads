package com.ai.walmartads.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(
        prefix = "walmart.ads.ai.kie",
        name = "enabled",
        havingValue = "false",
        matchIfMissing = true
)
public class DisabledAiAnalysisProvider implements AiAnalysisProvider {

    @Override
    public Optional<AiAnalysisResult> analyze(AiAnalysisRequest request) {
        return Optional.empty();
    }
}
