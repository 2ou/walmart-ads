package com.ai.walmartads.inventory.web;

import com.ai.walmartads.inventory.application.ForecastFactorService;
import com.ai.walmartads.inventory.application.InventoryDataService;
import com.ai.walmartads.inventory.application.ParentDailySalesOverrideService;
import com.ai.walmartads.inventory.application.ReplenishmentPlanCache;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/forecast/parent-daily-sales")
public class ParentDailySalesOverrideController {

    private final ParentDailySalesOverrideService service;
    private final ReplenishmentPlanCache replenishmentPlanCache;
    private final InventoryDataService inventoryDataService;

    public ParentDailySalesOverrideController(
            ParentDailySalesOverrideService service,
            ReplenishmentPlanCache replenishmentPlanCache,
            InventoryDataService inventoryDataService
    ) {
        this.service = service;
        this.replenishmentPlanCache = replenishmentPlanCache;
        this.inventoryDataService = inventoryDataService;
    }

    @GetMapping
    public ApiResponse<Map<String, Double>> list() {
        return ApiResponse.ok(service.list());
    }

    @PatchMapping("/{parentSku}")
    public ApiResponse<Map<String, Double>> update(
            @PathVariable String parentSku,
            @RequestBody ForecastFactorService.FactorPatch input
    ) {
        Map<String, Double> updated = service.update(parentSku, input);
        replenishmentPlanCache.invalidate();
        inventoryDataService.invalidateCache();
        return ApiResponse.ok(updated);
    }
}
