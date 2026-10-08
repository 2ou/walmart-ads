package com.ai.walmartads.analytics.web;

import com.ai.walmartads.analytics.application.ManualKeywordAnalysisService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/manual-keyword-analysis")
public class ManualKeywordAnalysisController {

    private final ManualKeywordAnalysisService service;

    public ManualKeywordAnalysisController(ManualKeywordAnalysisService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<ManualKeywordAnalysisService.Analysis> analyze(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(defaultValue = "SP") String adType
    ) {
        return ApiResponse.ok(service.analyze(startDate, endDate, adType));
    }
}
