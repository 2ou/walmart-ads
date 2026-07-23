package com.ai.walmartads.inventory.web;

import com.ai.walmartads.inventory.application.InventoryDataService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
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
            @RequestParam(defaultValue = "45") int targetDays
    ) {
        return ApiResponse.ok(inventoryDataService.load(days, targetDays));
    }

    @PostMapping("/production-stock")
    public ApiResponse<InventoryDataService.ProductionEntry> saveProductionStock(
            @RequestBody ProductionStockRequest request
    ) {
        return ApiResponse.ok(
                "做货库存已保存，并保留历史记录。",
                inventoryDataService.saveProductionStock(request.sku(), request.productionUnits(), request.note())
        );
    }

    @GetMapping("/production-stock/history")
    public ApiResponse<List<InventoryDataService.ProductionEntry>> productionHistory(
            @RequestParam String sku,
            @RequestParam(defaultValue = "20") int limit
    ) {
        return ApiResponse.ok(inventoryDataService.productionHistory(sku, limit));
    }

    public record ProductionStockRequest(
            String sku,
            BigDecimal productionUnits,
            String note
    ) {
    }
}
