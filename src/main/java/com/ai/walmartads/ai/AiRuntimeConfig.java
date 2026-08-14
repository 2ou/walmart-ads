package com.ai.walmartads.ai;

import com.ai.walmartads.config.KieAiProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 运行期配置。
 *
 * 启动时从 application.yml / 环境变量（KieAiProperties）播种，作为跨重启的
 * 持久化来源。之后可由前端通过 PUT /api/ai/config 在运行期临时覆盖，
 * 但仅对当前进程有效——进程重启后还原为 yml 配置。
 *
 * 设计取舍：相比此前基于数据库的加密持久化方案，本实现不再引入 JPA 实体
 * 与 AES 加解密，配置以明文落在受 gitignore 保护的外部 application.yml 中，
 * 换取更少的代码与运维复杂度（单机自用场景足够）。
 */
@Service
public class AiRuntimeConfig {

    private volatile boolean enabled;
    private volatile String apiKey;
    private volatile String baseUrl;
    private volatile String model;
    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;

    public AiRuntimeConfig(KieAiProperties properties) {
        this.enabled = properties.enabled();
        this.apiKey = properties.apiKey();
        this.baseUrl = defaultIfBlank(properties.baseUrl(), "https://api.kie.ai");
        this.model = defaultIfBlank(properties.model(), "gpt-5-6-terra");
        this.connectTimeoutSeconds = properties.connectTimeoutSeconds() > 0 ? properties.connectTimeoutSeconds() : 10;
        this.readTimeoutSeconds = properties.readTimeoutSeconds() > 0 ? properties.readTimeoutSeconds() : 60;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public boolean isConfigured() {
        return enabled && StringUtils.hasText(apiKey);
    }

    private static String defaultIfBlank(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}
