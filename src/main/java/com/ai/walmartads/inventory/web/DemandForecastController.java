package com.ai.walmartads.inventory.web;

import com.ai.walmartads.inventory.application.DemandForecastService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/inventory/demand-forecast")
public class DemandForecastController {

    private final DemandForecastService demandForecastService;

    public DemandForecastController(DemandForecastService demandForecastService) {
        this.demandForecastService = demandForecastService;
    }

    @GetMapping
    public ApiResponse<DemandForecastService.DemandForecastPayload> list() {
        return ApiResponse.ok(demandForecastService.load());
    }
}
