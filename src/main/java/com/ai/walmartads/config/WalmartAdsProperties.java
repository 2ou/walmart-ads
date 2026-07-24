package com.ai.walmartads.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.LocalDate;

@ConfigurationProperties(prefix = "walmart.ads")
public record WalmartAdsProperties(
        Path dataRoot,
        Path archiveRoot,
        Path historicalWorkbook,
        LocalDate historicalEndDateExclusive,
        Path skuMappingWorkbook,
        Path weeklyReportsRoot
) {
}
