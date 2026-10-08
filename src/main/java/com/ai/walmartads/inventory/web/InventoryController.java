package com.ai.walmartads.inventory.web;

import com.ai.walmartads.inventory.application.ForecastSettingService;
import com.ai.walmartads.inventory.application.InventoryDataService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private final InventoryDataService inventoryDataService;
    private final ForecastSettingService forecastSettingService;

    public InventoryController(InventoryDataService inventoryDataService,
                               ForecastSettingService forecastSettingService) {
        this.inventoryDataService = inventoryDataService;
        this.forecastSettingService = forecastSettingService;
    }

    @GetMapping("/data")
    public ApiResponse<InventoryDataService.InventoryPayload> data(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) Integer targetDays,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate
    ) {
        // 未显式指定时沿用库里保存的全局目标覆盖天数（原先硬编码 60，刷新即丢失）
        int effectiveTargetDays = forecastSettingService.resolve(targetDays);
        return ApiResponse.ok(inventoryDataService.load(days, effectiveTargetDays, startDate, endDate));
    }

    @GetMapping("/production-orders")
    public ApiResponse<List<InventoryDataService.ProductionOrderView>> productionOrders() {
        return ApiResponse.ok(inventoryDataService.productionOrders());
    }
}
