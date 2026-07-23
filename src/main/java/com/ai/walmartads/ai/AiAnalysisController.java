package com.ai.walmartads.ai;

import com.ai.walmartads.config.KieAiProperties;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiAnalysisController {

    private final AiAnalysisService service;
    private final KieAiProperties properties;

    public AiAnalysisController(AiAnalysisService service, KieAiProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @GetMapping("/status")
    public ApiResponse<AiStatus> status() {
        return ApiResponse.ok(new AiStatus(
                properties.enabled(),
                properties.configured(),
                "kie",
                properties.model(),
                "manual",
                30
        ));
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
            @RequestParam(defaultValue = "false") boolean force
    ) {
        return ApiResponse.ok("Kie AI 分析完成", service.analyze(days, force));
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
}
