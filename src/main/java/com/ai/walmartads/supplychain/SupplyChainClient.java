package com.ai.walmartads.supplychain;

import com.ai.walmartads.config.SupplyChainProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 穿针引线供应链只读 API 客户端。 */
public class SupplyChainClient {

    private static final Logger log = LoggerFactory.getLogger(SupplyChainClient.class);
    private static final String PRODUCTION_LIST = "/produce/order/productionOrder/list";
    private static final String PRODUCTION_DETAIL = "/produce/order/productionOrderDetail/{orderNo}";
    private static final String DELIVERY_LIST = "/produce/deliverGoods/deliverGoodsList";
    private static final String DELIVERY_DETAIL = "/produce/deliverGoods/deliverGoodsDetailList";

    private final SupplyChainProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private volatile String sessionToken;

    SupplyChainClient(SupplyChainProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.resolvedConnectTimeoutSeconds() * 1000);
        factory.setReadTimeout(properties.resolvedReadTimeoutSeconds() * 1000);
        this.restClient = RestClient.builder()
                .baseUrl(properties.resolvedBaseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public List<Map<String, Object>> fetchProductionOrders() {
        List<Map<String, Object>> rows = fetchPaged(PRODUCTION_LIST, Map.of("rpoFlag", "0"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (hasText(row, "stockKeepingUnit", "sku")) {
                result.add(row);
                continue;
            }
            String orderNo = text(row, "orderNo");
            if (!StringUtils.hasText(orderNo)) {
                continue;
            }
            List<Map<String, Object>> details = extractRows(get(PRODUCTION_DETAIL, Map.of(), orderNo));
            for (Map<String, Object> detail : details) {
                result.add(merge(row, detail));
            }
        }
        log.info("供应链生产中订单拉取完成：list={}, skuRows={}", rows.size(), result.size());
        return result;
    }

    public List<Map<String, Object>> fetchDeliverySheets() {
        List<Map<String, Object>> rows = fetchPaged(DELIVERY_LIST, Map.of());
        log.info("供应链生产发货单拉取完成：sheets={}", rows.size());
        return rows;
    }

    public List<Map<String, Object>> fetchDeliveryDetails(List<Map<String, Object>> sheets) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> sheet : sheets) {
            String deliveryNo = text(sheet, "deliverGoodsNo", "deliveryNo");
            if (!StringUtils.hasText(deliveryNo)) {
                continue;
            }
            List<Map<String, Object>> details = extractRows(get(
                    DELIVERY_DETAIL, Map.of("deliverGoodsNo", deliveryNo)));
            if (details.isEmpty() && hasText(sheet, "stockKeepingUnit", "sku")) {
                result.add(sheet);
            } else {
                for (Map<String, Object> detail : details) {
                    result.add(merge(sheet, detail));
                }
            }
        }
        log.info("供应链生产发货单明细拉取完成：sheets={}, skuRows={}", sheets.size(), result.size());
        return result;
    }

    private List<Map<String, Object>> fetchPaged(String path, Map<String, Object> baseParams) {
        List<Map<String, Object>> all = new ArrayList<>();
        int page = 1;
        int pageSize = properties.resolvedPageSize();
        while (page <= 1000) {
            Map<String, Object> params = new LinkedHashMap<>(baseParams);
            params.put("pageNum", page);
            params.put("pageSize", pageSize);
            JsonNode response = get(path, params);
            List<Map<String, Object>> rows = extractRows(response);
            if (rows.isEmpty()) {
                break;
            }
            all.addAll(rows);
            long total = extractTotal(response);
            if ((total >= 0 && all.size() >= total) || rows.size() < pageSize) {
                break;
            }
            page++;
        }
        return all;
    }

    private JsonNode get(String path, Map<String, Object> params, Object... pathVariables) {
        try {
            return executeGet(path, params, pathVariables);
        } catch (HttpClientErrorException.Unauthorized exception) {
            if (!StringUtils.hasText(properties.username())) {
                throw exception;
            }
            sessionToken = null;
            return executeGet(path, params, pathVariables);
        }
    }

    private JsonNode executeGet(String path, Map<String, Object> params, Object... pathVariables) {
        JsonNode response = restClient.get()
                .uri(builder -> {
                    builder.path(path);
                    params.forEach(builder::queryParam);
                    return builder.build(pathVariables);
                })
                .header(HttpHeaders.AUTHORIZATION, authorization())
                .retrieve()
                .body(JsonNode.class);
        validate(response, path);
        return response;
    }

    private synchronized String authorization() {
        if (StringUtils.hasText(properties.token())) {
            return bearer(properties.token());
        }
        if (StringUtils.hasText(sessionToken)) {
            return bearer(sessionToken);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", properties.username());
        body.put("password", properties.password());
        body.put("code", "");
        body.put("uuid", "");
        body.put("ignoreCode", true);
        JsonNode response = restClient.post()
                .uri(builder -> builder.path("/login").build())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        validate(response, "/login");
        String token = firstText(response, "token", "access_token");
        if (!StringUtils.hasText(token) && response != null) {
            token = firstText(response.path("data"), "token", "access_token");
        }
        if (!StringUtils.hasText(token)) {
            throw new IllegalStateException("供应链登录成功但响应中没有令牌");
        }
        sessionToken = token;
        return bearer(token);
    }

    private void validate(JsonNode response, String path) {
        if (response == null || response.isNull()) {
            throw new IllegalStateException("供应链接口返回为空：" + path);
        }
        JsonNode code = response.get("code");
        if (code != null && !code.isNull() && code.asInt(200) != 200) {
            String message = response.path("msg").asText(response.path("message").asText("未知错误"));
            throw new IllegalStateException("供应链接口失败：" + message + " (" + path + ")");
        }
    }

    private List<Map<String, Object>> extractRows(JsonNode response) {
        JsonNode rows = response == null ? null : response.get("rows");
        if (rows == null || !rows.isArray()) {
            JsonNode data = response == null ? null : response.get("data");
            if (data != null && data.isArray()) {
                rows = data;
            } else if (data != null) {
                rows = firstArray(data, "rows", "list", "records", "productionOrderDetailList",
                        "deliverGoodsDetailList", "detailList");
                if ((rows == null || !rows.isArray()) && data.isObject()
                        && hasTextNode(data, "stockKeepingUnit", "sku", "orderNo", "deliverGoodsNo")) {
                    rows = objectMapper.createArrayNode().add(data);
                }
            }
        }
        if (rows == null || !rows.isArray()) {
            rows = response == null ? null : response.get("list");
        }
        if (rows == null || !rows.isArray()) {
            return List.of();
        }
        return objectMapper.convertValue(rows, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    private static JsonNode firstArray(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode candidate = node.get(key);
            if (candidate != null && candidate.isArray()) {
                return candidate;
            }
        }
        return null;
    }

    private static long extractTotal(JsonNode response) {
        if (response == null) {
            return -1;
        }
        if (response.has("total")) {
            return response.path("total").asLong(-1);
        }
        return response.path("data").path("total").asLong(-1);
    }

    private static Map<String, Object> merge(Map<String, Object> parent, Map<String, Object> detail) {
        Map<String, Object> merged = new LinkedHashMap<>(parent);
        detail.forEach((key, value) -> {
            if (value != null && (!(value instanceof String text) || !text.isBlank())) {
                merged.put(key, value);
            }
        });
        return merged;
    }

    private static boolean hasText(Map<String, Object> row, String... keys) {
        return StringUtils.hasText(text(row, keys));
    }

    private static boolean hasTextNode(JsonNode node, String... keys) {
        for (String key : keys) {
            if (node.hasNonNull(key) && !node.path(key).asText().isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String text(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return null;
    }

    private static String firstText(JsonNode node, String... keys) {
        if (node == null) {
            return null;
        }
        for (String key : keys) {
            String value = node.path(key).asText(null);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private static String bearer(String token) {
        String value = token.trim();
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value : "Bearer " + value;
    }
}
