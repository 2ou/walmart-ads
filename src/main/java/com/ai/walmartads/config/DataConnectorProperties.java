package com.ai.walmartads.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.time.Duration;

/** 项目内置浏览器数据连接器配置。 */
@ConfigurationProperties(prefix = "walmart.ads.data-connector")
public record DataConnectorProperties(
        boolean enabled,
        String chromePath,
        String dataDir,
        String cron,
        String zone,
        int navigationTimeoutSeconds,
        int captureTimeoutSeconds,
        int loginTimeoutMinutes,
        int logRetentionDays
) {

    public Path resolvedDataDir() {
        String value = StringUtils.hasText(dataDir) ? dataDir.trim() : "D:/WorkBuddyData/walmart-ads/connector";
        return Path.of(value).toAbsolutePath().normalize();
    }

    public String resolvedCron() {
        return StringUtils.hasText(cron) ? cron.trim() : "0 0 8 * * *";
    }

    public String resolvedZone() {
        return StringUtils.hasText(zone) ? zone.trim() : "Asia/Shanghai";
    }

    public Duration navigationTimeout() {
        return Duration.ofSeconds(navigationTimeoutSeconds <= 0 ? 60 : navigationTimeoutSeconds);
    }

    public Duration captureTimeout() {
        return Duration.ofSeconds(captureTimeoutSeconds <= 0 ? 75 : captureTimeoutSeconds);
    }

    public Duration loginTimeout() {
        return Duration.ofMinutes(loginTimeoutMinutes <= 0 ? 10 : loginTimeoutMinutes);
    }

    public int resolvedLogRetentionDays() {
        return logRetentionDays <= 0 ? 30 : logRetentionDays;
    }
}
