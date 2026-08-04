package com.ai.walmartads.inventory.web;

import com.ai.walmartads.inventory.application.ReplenishmentPlanCache;
import com.ai.walmartads.inventory.application.ReplenishmentPlanningService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/forecast/recommendations")
public class ReplenishmentPlanningController {

    private final ReplenishmentPlanCache cache;

    public ReplenishmentPlanningController(ReplenishmentPlanCache cache) {
        this.cache = cache;
    }

    @GetMapping
    public ApiResponse<ReplenishmentPlanningService.PlanningPayload> plan() {
        return ApiResponse.ok(cache.current());
    }

    @PostMapping("/recalculate")
    public ApiResponse<ReplenishmentPlanningService.PlanningPayload> recalculate() {
        return ApiResponse.ok(cache.refresh());
    }
}
