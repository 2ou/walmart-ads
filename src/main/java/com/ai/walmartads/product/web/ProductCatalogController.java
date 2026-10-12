package com.ai.walmartads.product.web;

import com.ai.walmartads.product.application.ProductCatalogService;
import com.ai.walmartads.product.application.ProductCatalogService.SaveProfitAssumptionRequest;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 产品库接口。
 *
 * <p><b>为什么带 ETag + no-cache</b>：产品库列表一次要返回近 105KB、详情（80 变体款）328KB，
 * 而用户的高频动作是「来回切模块 / 反复开同一个款」。此前响应没有任何缓存协商头，
 * 浏览器每次都要整包重取、服务端每次都要从头重算。现在：
 * <ul>
 *   <li>响应带 {@code ETag}（= 数据指纹）与 {@code Cache-Control: no-cache, private}
 *       —— 允许缓存但强制校验；</li>
 *   <li>校验命中（数据没变）直接 <b>304</b>，零响应体、零服务端计算；</li>
 *   <li>数据变了指纹就变，自动回落到 200 全量，不会出现陈旧数据。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/products")
public class ProductCatalogController {

    /** 允许浏览器缓存，但每次使用前必须回源校验（配合 ETag 即 304 快路径）。 */
    private static final CacheControl REVALIDATE = CacheControl.noCache().cachePrivate();

    private final ProductCatalogService productCatalogService;

    public ProductCatalogController(ProductCatalogService productCatalogService) {
        this.productCatalogService = productCatalogService;
    }

    @GetMapping("/catalog")
    public ResponseEntity<ApiResponse<ProductCatalogService.CatalogPayload>> catalog(
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        ProductCatalogService.CatalogResult result = productCatalogService.loadWithVersion();
        String etag = etagOf(result.version());
        if (matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(304).eTag(etag).cacheControl(REVALIDATE).build();
        }
        return ResponseEntity.ok().eTag(etag).cacheControl(REVALIDATE).body(ApiResponse.ok(result.payload()));
    }

    @GetMapping("/{parentSku}")
    public ResponseEntity<ApiResponse<ProductCatalogService.ProductDetail>> detail(
            @PathVariable String parentSku,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        String etag = etagOf(productCatalogService.detailVersion(parentSku));
        if (matches(ifNoneMatch, etag)) {
            return ResponseEntity.status(304).eTag(etag).cacheControl(REVALIDATE).build();
        }
        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(REVALIDATE)
                .body(ApiResponse.ok(productCatalogService.detail(parentSku)));
    }

    /** 详情抽屉「查看上传表原始字段」折叠块展开时按需拉取，不再随详情整包下发。 */
    @GetMapping("/{parentSku}/raw-fields")
    public ApiResponse<Map<String, Object>> rawFields(@PathVariable String parentSku) {
        return ApiResponse.ok(productCatalogService.rawFields(parentSku));
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

    @PatchMapping("/parent/{parentSku}/refund-rate")
    public ApiResponse<Map<String, Object>> updateRefundRate(
            @PathVariable String parentSku,
            @RequestBody Map<String, Object> body
    ) {
        Object value = body.get("refundRate");
        BigDecimal refundRate = value == null ? null
                : (value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value).trim()));
        productCatalogService.updateRefundRate(parentSku, refundRate);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("parentSku", parentSku);
        result.put("refundRate", refundRate);
        return ApiResponse.ok(result);
    }

    @PostMapping("/launch-dates/backfill")
    public ApiResponse<Map<String, Object>> backfillLaunchDates() {
        int updated = productCatalogService.backfillLaunchDates();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("updatedProductRecords", updated);
        return ApiResponse.ok(result);
    }

    /** 指纹可能很长，收紧成短哈希作为 ETag 值（同版本必同哈希，不同版本几乎必不同）。 */
    private static String etagOf(String version) {
        String safe = version == null ? "" : version;
        return "\"c" + Integer.toHexString(safe.hashCode()) + "-" + safe.length() + "\"";
    }

    private static boolean matches(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || ifNoneMatch.isBlank()) {
            return false;
        }
        String header = ifNoneMatch.trim();
        if ("*".equals(header)) {
            return true;
        }
        // 可能带 W/ 弱校验前缀，或逗号分隔多个候选值。
        for (String candidate : header.split(",")) {
            String value = candidate.trim();
            if (value.startsWith("W/")) {
                value = value.substring(2).trim();
            }
            if (value.equals(etag)) {
                return true;
            }
        }
        return false;
    }
}
