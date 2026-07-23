package com.ai.walmartads.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

@ConfigurationProperties(prefix = "walmart.ads.ai.kie")
public record KieAiProperties(
        boolean enabled,
        String apiKey,
        String baseUrl,
        String model,
        int connectTimeoutSeconds,
        int readTimeoutSeconds,
        int maxCampaigns,
        int maxSkus
) {

    public boolean configured() {
        return enabled && StringUtils.hasText(apiKey);
    }

    public String chatCompletionsPath() {
        return "/" + model + "/v1/chat/completions";
    }
}
