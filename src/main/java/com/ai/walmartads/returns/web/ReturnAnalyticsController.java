package com.ai.walmartads.returns.web;

import com.ai.walmartads.returns.application.ReturnAnalyticsService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/returns")
public class ReturnAnalyticsController {

    private final ReturnAnalyticsService returnAnalyticsService;

    public ReturnAnalyticsController(ReturnAnalyticsService returnAnalyticsService) {
        this.returnAnalyticsService = returnAnalyticsService;
    }

    @GetMapping("/data")
    public ApiResponse<ReturnAnalyticsService.ReturnPayload> data(
            @RequestParam(required = false) String month
    ) {
        return ApiResponse.ok(returnAnalyticsService.load(month));
    }
}
