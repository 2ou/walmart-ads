package com.ai.walmartads.inventory.web;

import com.ai.walmartads.inventory.application.ForecastFactorService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/forecast/factors")
public class ForecastFactorController {

    private final ForecastFactorService forecastFactorService;

    public ForecastFactorController(ForecastFactorService forecastFactorService) {
        this.forecastFactorService = forecastFactorService;
    }

    @GetMapping
    public ApiResponse<List<ForecastFactorService.ForecastFactorDto>> list() {
        return ApiResponse.ok(forecastFactorService.list());
    }

    @PatchMapping("/{sku}")
    public ApiResponse<ForecastFactorService.ForecastFactorDto> update(
            @PathVariable String sku,
            @RequestBody ForecastFactorService.FactorPatch input
    ) {
        return ApiResponse.ok(forecastFactorService.update(sku, input));
    }
}
