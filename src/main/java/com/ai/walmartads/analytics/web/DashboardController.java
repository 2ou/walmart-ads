package com.ai.walmartads.analytics.web;

import com.ai.walmartads.analytics.application.DashboardDataService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DashboardDataService dashboardDataService;

    public DashboardController(DashboardDataService dashboardDataService) {
        this.dashboardDataService = dashboardDataService;
    }

    @GetMapping("/data")
    public DashboardDataService.DashboardPayload data() {
        return dashboardDataService.loadDashboard();
    }

    @GetMapping("/bootstrap")
    public DashboardDataService.DashboardPayload bootstrap() {
        return dashboardDataService.bootstrap();
    }

    @GetMapping("/recommendations")
    public ApiResponse<List<DashboardDataService.Recommendation>> recommendations(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "5.0") double targetRoas,
            @RequestParam(defaultValue = "1.5") double floorRoas,
            @RequestParam(defaultValue = "20") int minClicks,
            @RequestParam(defaultValue = "10") double minSpend
    ) {
        return ApiResponse.ok(dashboardDataService.recommendations(
                Math.max(1, Math.min(days, 365)),
                Math.max(0, targetRoas),
                Math.max(0, floorRoas),
                Math.max(0, minClicks),
                Math.max(0, minSpend)
        ));
    }
}
