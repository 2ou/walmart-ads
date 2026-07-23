package com.ai.walmartads.ai;

import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class DisabledAiAnalysisProvider implements AiAnalysisProvider {

    @Override
    public Optional<AiAnalysisResult> analyze(AiAnalysisRequest request) {
        return Optional.empty();
    }
}
