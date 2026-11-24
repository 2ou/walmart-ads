package com.ai.walmartads.inventory.web;

import com.ai.walmartads.inventory.application.ForecastSettingService;
import com.ai.walmartads.inventory.application.InventoryDataService;
import com.ai.walmartads.inventory.application.LingxingDomesticInventoryService;
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
    private final LingxingDomesticInventoryService lingxingDomesticInventoryService;

    public InventoryController(InventoryDataService inventoryDataService,
                               ForecastSettingService forecastSettingService,
                               LingxingDomesticInventoryService lingxingDomesticInventoryService) {
        this.inventoryDataService = inventoryDataService;
        this.forecastSettingService = forecastSettingService;
        this.lingxingDomesticInventoryService = lingxingDomesticInventoryService;
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

    @GetMapping("/coverage")
    public ApiResponse<InventoryDataService.InventoryCoveragePayload> coverage(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) Integer targetDays
    ) {
        return ApiResponse.ok(inventoryDataService.loadCoverage(days, forecastSettingService.resolve(targetDays)));
    }

    @GetMapping("/window")
    public ApiResponse<InventoryDataService.InventoryWindowPayload> window(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) Integer targetDays,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "all") String risk,
            @RequestParam(defaultValue = "parentSku") String sort,
            @RequestParam(defaultValue = "asc") String direction
    ) {
        int effectiveTargetDays = forecastSettingService.resolve(targetDays);
        return ApiResponse.ok(inventoryDataService.loadWindow(
                days, effectiveTargetDays, startDate, endDate,
                page, size, search, risk, sort, direction));
    }

    @GetMapping("/style")
    public ApiResponse<InventoryDataService.InventoryStylePayload> style(
            @RequestParam String parentSku,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) Integer targetDays,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate
    ) {
        int effectiveTargetDays = forecastSettingService.resolve(targetDays);
        return ApiResponse.ok(inventoryDataService.loadStyle(
                days, effectiveTargetDays, startDate, endDate, parentSku));
    }

    @GetMapping("/trend")
    public ApiResponse<List<InventoryDataService.TrendRow>> trend() {
        return ApiResponse.ok(inventoryDataService.trend());
    }

    @GetMapping("/production-orders")
    public ApiResponse<List<InventoryDataService.ProductionOrderView>> productionOrders() {
        return ApiResponse.ok(inventoryDataService.productionOrders());
    }

    @GetMapping("/domestic-source")
    public ApiResponse<LingxingDomesticInventoryService.SourceStatus> domesticSource() {
        return ApiResponse.ok(lingxingDomesticInventoryService.status());
    }
}
