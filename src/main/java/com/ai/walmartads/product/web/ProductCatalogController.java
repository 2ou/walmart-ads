package com.ai.walmartads.product.web;

import com.ai.walmartads.product.application.ProductCatalogService;
import com.ai.walmartads.product.application.ProductCatalogService.SaveProfitAssumptionRequest;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/products")
public class ProductCatalogController {

    private final ProductCatalogService productCatalogService;

    public ProductCatalogController(ProductCatalogService productCatalogService) {
        this.productCatalogService = productCatalogService;
    }

    @GetMapping("/catalog")
    public ApiResponse<ProductCatalogService.CatalogPayload> catalog() {
        return ApiResponse.ok(productCatalogService.load());
    }

    @GetMapping("/{parentSku}")
    public ApiResponse<ProductCatalogService.ProductDetail> detail(@PathVariable String parentSku) {
        return ApiResponse.ok(productCatalogService.detail(parentSku));
    }

    @PutMapping("/profit-assumption")
    public ApiResponse<ProductCatalogService.ProfitAssumptionView> saveProfitAssumption(
            @RequestBody SaveProfitAssumptionRequest request
    ) {
        return ApiResponse.ok(productCatalogService.saveProfitAssumption(request));
    }

    @PatchMapping("/{id}/cutting-yield")
    public ApiResponse<Map<String, Object>> updateCuttingYield(
            @PathVariable Long id,
            @RequestBody Map<String, Object> body
    ) {
        Object value = body.get("cuttingYield");
        Integer cuttingYield = value == null ? null
                : (value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value).trim()));
        productCatalogService.updateCuttingYield(id, cuttingYield);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", id);
        result.put("cuttingYield", cuttingYield);
        return ApiResponse.ok(result);
    }

    @PatchMapping("/parent/{parentSku}/cutting-yield")
    public ApiResponse<Map<String, Object>> updateCuttingYieldByParent(
            @PathVariable String parentSku,
            @RequestBody Map<String, Object> body
    ) {
        Object value = body.get("cuttingYield");
        Integer cuttingYield = value == null ? null
                : (value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value).trim()));
        productCatalogService.updateCuttingYieldByParent(parentSku, cuttingYield);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("parentSku", parentSku);
        result.put("cuttingYield", cuttingYield);
        return ApiResponse.ok(result);
    }

    @PostMapping("/launch-dates/backfill")
    public ApiResponse<Map<String, Object>> backfillLaunchDates() {
        int updated = productCatalogService.backfillLaunchDates();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updatedProductRecords", updated);
        return ApiResponse.ok(result);
    }
}
