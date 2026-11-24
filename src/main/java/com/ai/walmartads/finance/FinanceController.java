package com.ai.walmartads.finance;

import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/finance")
public class FinanceController {

    private final FinanceAnalyticsService analyticsService;

    public FinanceController(FinanceAnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/overview")
    public ApiResponse<FinanceAnalyticsService.OverviewPayload> overview(
            @RequestParam(required = false) String startMonth,
            @RequestParam(required = false) String endMonth,
            @RequestParam(defaultValue = "true") boolean includeEstimate,
            @RequestParam(required = false) LocalDate customStartDate,
            @RequestParam(required = false) LocalDate customEndDate,
            @RequestParam(required = false) BigDecimal exchangeRate,
            @RequestParam(required = false) BigDecimal offsiteFeeCny
    ) {
        return ApiResponse.ok(analyticsService.overview(
                startMonth,
                endMonth,
                includeEstimate,
                customStartDate,
                customEndDate,
                exchangeRate,
                offsiteFeeCny
        ));
    }

    @GetMapping("/styles/{parentSku}")
    public ApiResponse<FinanceAnalyticsService.StyleDetailPayload> styleDetail(
            @PathVariable String parentSku,
            @RequestParam(defaultValue = "true") boolean includeEstimate
    ) {
        return ApiResponse.ok(analyticsService.styleDetail(parentSku, includeEstimate));
    }
}
