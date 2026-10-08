package com.ai.walmartads.analytics.web;

import com.ai.walmartads.analytics.application.CampaignStyleDiagnosisService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/campaign-diagnosis")
public class CampaignStyleDiagnosisController {

    private final CampaignStyleDiagnosisService service;

    public CampaignStyleDiagnosisController(CampaignStyleDiagnosisService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<CampaignStyleDiagnosisService.DiagnosisResponse> diagnose(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(defaultValue = "true") boolean details
    ) {
        return ApiResponse.ok(details
                ? service.diagnose(startDate, endDate)
                : service.diagnoseSummary(startDate, endDate));
    }

    @GetMapping("/style")
    public ApiResponse<CampaignStyleDiagnosisService.StyleDiagnosis> style(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam String parentSku
    ) {
        return ApiResponse.ok(service.styleDiagnosis(startDate, endDate, parentSku));
    }

    @GetMapping("/history")
    public ApiResponse<CampaignStyleDiagnosisService.HistoryResponse> history(
            @RequestParam String parentSku
    ) {
        return ApiResponse.ok(service.history(parentSku));
    }
}
