package com.ai.walmartads.product.web;

import com.ai.walmartads.product.application.ProductCatalogService;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
