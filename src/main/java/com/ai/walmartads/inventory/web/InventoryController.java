package com.ai.walmartads.inventory.web;

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

    public InventoryController(InventoryDataService inventoryDataService) {
        this.inventoryDataService = inventoryDataService;
    }

    @GetMapping("/data")
    public ApiResponse<InventoryDataService.InventoryPayload> data(
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(defaultValue = "60") int targetDays,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate
    ) {
        return ApiResponse.ok(inventoryDataService.load(days, targetDays, startDate, endDate));
    }

    @GetMapping("/production-orders")
    public ApiResponse<List<InventoryDataService.ProductionOrderView>> productionOrders() {
        return ApiResponse.ok(inventoryDataService.productionOrders());
    }
}
