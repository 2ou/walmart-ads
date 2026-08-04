package com.ai.walmartads.inventory.web;

import com.ai.walmartads.inventory.application.ForecastFactorService;
import com.ai.walmartads.inventory.application.ParentDailySalesOverrideService;
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

    public ParentDailySalesOverrideController(ParentDailySalesOverrideService service) {
        this.service = service;
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
        return ApiResponse.ok(service.update(parentSku, input));
    }
}
