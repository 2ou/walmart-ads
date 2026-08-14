package com.ai.walmartads.ai;

import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/ai")
public class AiAnalysisController {

    private final AiAnalysisService service;
    private final AiRuntimeConfig config;

    public AiAnalysisController(
            AiAnalysisService service,
            AiRuntimeConfig config
    ) {
        this.service = service;
        this.config = config;
    }

    @GetMapping("/status")
    public ApiResponse<AiStatus> status() {
        return ApiResponse.ok(new AiStatus(
                config.isEnabled(),
                config.isConfigured(),
                "kie",
                config.getModel(),
                "manual",
                30
        ));
    }

    @GetMapping("/config")
    public ApiResponse<AiConfigView> config() {
        return ApiResponse.ok(new AiConfigView(
                config.isEnabled(),
                StringUtils.hasText(config.getApiKey()),
                maskKey(config.getApiKey()),
                config.getBaseUrl(),
                config.getModel()
        ));
    }

    @PutMapping("/config")
    public ApiResponse<AiConfigView> updateConfig(@RequestBody AiConfigUpdate update) {
        if (update.enabled() != null) {
            config.setEnabled(update.enabled());
        }
        if (update.clearApiKey() != null && update.clearApiKey()) {
            config.setApiKey(null);
        } else if (update.apiKey() != null && StringUtils.hasText(update.apiKey())) {
            config.setApiKey(update.apiKey());
        }
        // apiKey 留空（null 或空串）且未要求清空 → 保留现有配置，不清除
        if (StringUtils.hasText(update.baseUrl())) {
            config.setBaseUrl(update.baseUrl());
        }
        if (StringUtils.hasText(update.model())) {
            config.setModel(update.model());
        }
        return config();
    }

    @GetMapping("/analysis/latest")
    public ApiResponse<AiAnalysisService.AiAnalysisView> latest() {
        return service.latest()
                .map(ApiResponse::ok)
                .orElseGet(() -> ApiResponse.ok("暂无 AI 分析记录", null));
    }

    @PostMapping("/analysis")
    public ApiResponse<AiAnalysisService.AiAnalysisView> analyze(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate
    ) {
        return ApiResponse.ok("Kie AI 分析完成", service.analyze(days, force, startDate, endDate));
    }

    public record AiStatus(
            boolean enabled,
            boolean configured,
            String provider,
            String model,
            String callMode,
            int maxPeriodDays
    ) {
    }

    public record AiConfigView(
            boolean enabled,
            boolean hasKey,
            String maskedKey,
            String baseUrl,
            String model
    ) {
    }

    public record AiConfigUpdate(
            Boolean enabled,
            String apiKey,
            String baseUrl,
            String model,
            Boolean clearApiKey
    ) {
    }

    private static String maskKey(String key) {
        if (!StringUtils.hasText(key)) {
            return null;
        }
        if (key.length() <= 4) {
            return "****";
        }
        return "****" + key.substring(key.length() - 4);
    }
}
