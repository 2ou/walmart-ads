package com.ai.walmartads.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;

/** 穿针引线供应链只读同步配置。 */
@ConfigurationProperties(prefix = "walmart.ads.supply-chain")
public record SupplyChainProperties(
        boolean enabled,
        String baseUrl,
        String token,
        String username,
        String password,
        String cron,
        String zone,
        int pageSize,
        int connectTimeoutSeconds,
        int readTimeoutSeconds
) {

    public boolean configured() {
        return enabled && (StringUtils.hasText(token)
                || (StringUtils.hasText(username) && StringUtils.hasText(password)));
    }

    public String resolvedBaseUrl() {
        String value = StringUtils.hasText(baseUrl)
                ? baseUrl.trim()
                : "https://czyxgyl.shangyiku.top/prod-api";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public String resolvedCron() {
        return StringUtils.hasText(cron) ? cron : "0 0 8 * * *";
    }

    public String resolvedZone() {
        return StringUtils.hasText(zone) ? zone : "Asia/Shanghai";
    }

    public int resolvedPageSize() {
        return pageSize <= 0 ? 200 : Math.min(pageSize, 500);
    }

    public int resolvedConnectTimeoutSeconds() {
        return connectTimeoutSeconds <= 0 ? 10 : connectTimeoutSeconds;
    }

    public int resolvedReadTimeoutSeconds() {
        return readTimeoutSeconds <= 0 ? 60 : readTimeoutSeconds;
    }
}
