package com.ai.walmartads.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

@ConfigurationProperties(prefix = "walmart.ads")
public record WalmartAdsProperties(
        LocalDate historicalEndDateExclusive
) {
}
