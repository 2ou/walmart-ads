package com.ai.walmartads.analytics.web;

import com.ai.walmartads.analytics.application.AdProductService;
import com.ai.walmartads.analytics.application.RecentDataService;
import com.ai.walmartads.analytics.application.StyleSalesService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recent")
public class RecentDataController {

    private final RecentDataService recentDataService;
    private final StyleSalesService styleSalesService;
    private final AdProductService adProductService;

    public RecentDataController(
            RecentDataService recentDataService,
            StyleSalesService styleSalesService,
            AdProductService adProductService
    ) {
        this.recentDataService = recentDataService;
        this.styleSalesService = styleSalesService;
        this.adProductService = adProductService;
    }

    @GetMapping("/daily")
    public ApiResponse<RecentDataService.RecentDataPayload> daily(
            @RequestParam(defaultValue = "30") int days
    ) {
        return ApiResponse.ok(recentDataService.load(Math.max(1, Math.min(days, 365))));
    }

    @GetMapping("/styles")
    public ApiResponse<StyleSalesService.StyleSalesPayload> styles(
            @RequestParam(defaultValue = "30") int days
    ) {
        return ApiResponse.ok(styleSalesService.load(Math.max(1, Math.min(days, 365))));
    }

    @GetMapping("/ad-products")
    public ApiResponse<AdProductService.AdProductPayload> adProducts(
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(defaultValue = "false") boolean latest
    ) {
        return ApiResponse.ok(adProductService.load(Math.max(1, Math.min(days, 365)), latest));
    }
}
