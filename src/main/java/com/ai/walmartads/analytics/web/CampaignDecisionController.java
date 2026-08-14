package com.ai.walmartads.analytics.web;

import com.ai.walmartads.analytics.application.CampaignDecisionConfig;
import com.ai.walmartads.analytics.application.CampaignDecisionService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/decision")
public class CampaignDecisionController {

    private final CampaignDecisionService service;

    public CampaignDecisionController(CampaignDecisionService service) {
        this.service = service;
    }

    @GetMapping("/analysis")
    public ApiResponse<CampaignDecisionService.DecisionAnalysis> analysis(@RequestParam(defaultValue = "7") int periodDays) {
        return ApiResponse.ok(service.classify(periodDays));
    }

    @GetMapping("/config")
    public ApiResponse<List<CampaignDecisionConfig>> config() {
        return ApiResponse.ok(service.getConfigs());
    }

    @PutMapping("/config")
    public ApiResponse<CampaignDecisionConfig> saveConfig(@RequestBody CampaignDecisionConfig config) {
        return ApiResponse.ok("已保存决策阈值", service.saveConfig(config));
    }
}
