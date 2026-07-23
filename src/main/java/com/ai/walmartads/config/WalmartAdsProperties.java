package com.ai.walmartads.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;

@ConfigurationProperties(prefix = "walmart.ads")
public record WalmartAdsProperties(
        Path dataRoot,
        Path archiveRoot
) {
}
