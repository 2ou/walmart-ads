package com.ai.walmartads.supplychain;

import com.ai.walmartads.config.SupplyChainProperties;
import com.ai.walmartads.connector.ConnectorSystem;
import com.ai.walmartads.connector.DataConnectorSessionService;
import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspacePrincipal;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** 穿针引线供应链只读 API 客户端。 */
public class SupplyChainClient {

    private static final Logger log = LoggerFactory.getLogger(SupplyChainClient.class);
    /** 物流信息登记只读的货件状态：1=待确认、2=已完成。 */
    private static final Set<String> KEPT_SHIPMENT_STATUSES = Set.of("1", "2", "待确认", "已完成");
    private static final String ORDER_LIST = "/produce/order/orderList";
    private static final String PRODUCTION_LIST = "/produce/order/productionOrder/list";
    private static final String PRODUCTION_DETAIL = "/produce/order/productionOrderDetail/{orderNo}";
    private static final String DELIVERY_LIST = "/produce/deliverGoods/deliverGoodsList";
    private static final String DELIVERY_DETAIL = "/produce/deliverGoods/deliverGoodsDetailList";
    private static final String LOGISTICS_REGISTER_LIST = "/logistics/register/list";
    private static final String CURRENT_USER = "/getInfo";
    private static final Set<String> OPERATIONS_ROLES = Set.of("YY", "YYZG", "YYZL", "YYJL");
    private static final Set<String> WAREHOUSE_ROLES = Set.of("CKZG", "CKWY", "CKZJ");

    private final SupplyChainProperties properties;
    private final ObjectMapper objectMapper;
    private final DataConnectorSessionService connectorSessions;
    private final RestClient restClient;
    private volatile String sessionToken;

    SupplyChainClient(
            SupplyChainProperties properties,
            ObjectMapper objectMapper,
            DataConnectorSessionService connectorSessions
    ) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.connectorSessions = connectorSessions;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.resolvedConnectTimeoutSeconds() * 1000);
        factory.setReadTimeout(properties.resolvedReadTimeoutSeconds() * 1000);
        this.restClient = RestClient.builder()
                .baseUrl(properties.resolvedBaseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public List<Map<String, Object>> fetchOrders() {
        List<Map<String, Object>> rows = fetchPaged(ORDER_LIST, Map.of("rpoFlag", "0"));
        log.info("供应链生产订单拉取完成：orders={}", rows.size());
        return rows;
    }

    public boolean configured() {
        return properties.enabled() && (properties.configured() || connectorSessions.currentSupplyConfigured());
    }

    public List<Map<String, Object>> fetchProductionOrders() {
        List<Map<String, Object>> rows = fetchPaged(PRODUCTION_LIST,
                Map.of("rpoFlag", "0", "orderStatus", "2"));
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

    public List<Map<String, Object>> fetchLogisticsRegisters() {
        WorkspacePrincipal principal = WorkspaceContext.requirePrincipal();
        Map<String, Object> roleScope = logisticsRoleScope();
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<Map<String, Object>>>> tasks = new ArrayList<>();
            for (int flowStatus = 1; flowStatus <= 5; flowStatus++) {
                for (int shipmentStatus = 1; shipmentStatus <= 2; shipmentStatus++) {
                    int flow = flowStatus;
                    int shipment = shipmentStatus;
                    tasks.add(executor.submit(() -> WorkspaceContext.callAs(principal,
                            () -> {
                                Map<String, Object> params = new LinkedHashMap<>(roleScope);
                                params.put("flowStatus", flow);
                                params.put("shipmentStatus", shipment);
                                return fetchPaged(LOGISTICS_REGISTER_LIST, params);
                            })));
                }
            }
            for (Future<List<Map<String, Object>>> task : tasks) {
                for (Map<String, Object> row : task.get()) {
                    String identity = text(row, "id", "registerId");
                    if (!StringUtils.hasText(identity)) {
                        identity = String.join("|",
                                value(text(row, "fbaNumber", "shipmentId")),
                                value(text(row, "accountNumber", "account")),
                                value(text(row, "batchNumber")),
                                value(text(row, "takeGoodsNo")),
                                value(text(row, "logisticsNo", "trackingNo")));
                        if ("||||".equals(identity)) {
                            identity = "row:" + row.hashCode();
                        }
                    }
                    unique.putIfAbsent(identity, row);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("供应链物流信息登记读取被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw new IllegalStateException("供应链物流信息登记读取失败：" + cause.getMessage(), cause);
        }
        List<Map<String, Object>> all = new ArrayList<>(unique.values());
        List<Map<String, Object>> rows = all.stream()
                .filter(row -> keepShipmentStatus(text(row, "shipmentStatus")))
                .toList();
        long operating = rows.stream()
                .filter(row -> isOperatingFlow(text(row, "flowStatus")))
                .count();
        log.info("供应链物流信息登记拉取完成：五种流程状态合计 registers={}，货件状态白名单（待确认/已完成）保留 {}，"
                        + "其中运营登记（仅发货预报、无货代渠道）{} 条照常入库、工作台不展示",
                all.size(), rows.size(), operating);
        return rows;
    }

    /** 与供应链网页保持同一角色范围，避免 YY 账号误拉取平台全部物流历史。 */
    private Map<String, Object> logisticsRoleScope() {
        JsonNode rolesNode = get(CURRENT_USER, Map.of()).path("roles");
        Set<String> roles = new HashSet<>();
        if (rolesNode.isArray()) {
            rolesNode.forEach(role -> {
                String value = role.asText("").trim().toUpperCase();
                if (!value.isEmpty()) roles.add(value);
            });
        }
        boolean yy = roles.stream().anyMatch(OPERATIONS_ROLES::contains);
        boolean warehouse = roles.stream().anyMatch(WAREHOUSE_ROLES::contains);
        boolean logisticsProvider = roles.contains("WLS");
        log.info("供应链物流查询角色范围：YYFlag={}, CKFlag={}, WLSFlag={}",
                yy, warehouse, logisticsProvider);
        return Map.of("YYFlag", yy, "CKFlag", warehouse, "WLSFlag", logisticsProvider);
    }

    /**
     * 流程状态：识别「1 运营登记」。
     *
     * <p>运营登记是运营刚建单、尚未提交物流商的阶段 —— 这类记录没有货代、渠道、物流单号，也没有启运和签收日期，
     * 供应链系统的物流信息登记页同样不把它算进「待确认 / 已完成」。
     *
     * <p>处理口径（2026-10-19 花定调）：**照常同步入库，工作台不展示**。
     * 全量快照保留完整，展示侧在 {@code SupplyChainWorkspaceService.loadLogistics()} 过滤。
     */
    private static boolean isOperatingFlow(String status) {
        return StringUtils.hasText(status) && "1".equals(status.trim());
    }

    /**
     * 货件状态白名单：只读「待确认 / 已完成」。
     *
     * <p>状态缺失（老表或上游字段调整）按保留处理 —— 宁可多存一条，也不要因为字段改名把整批物流数据清空。
     * 该过滤同时兜住了「作废 / 已取消」等未结案之外的脏状态。
     */
    private static boolean keepShipmentStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return true;
        }
        return KEPT_SHIPMENT_STATUSES.contains(status.trim());
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
        Set<Integer> seenPageFingerprints = new HashSet<>();
        Set<String> seenRows = new HashSet<>();
        int page = 1;
        int pageSize = properties.resolvedPageSize();
        while (page <= 100) {
            Map<String, Object> params = new LinkedHashMap<>(baseParams);
            params.put("pageNum", page);
            params.put("pageSize", pageSize);
            JsonNode response = get(path, params);
            List<Map<String, Object>> rows = extractRows(response);
            if (rows.isEmpty()) {
                break;
            }
            int pageFingerprint = rows.hashCode();
            if (!seenPageFingerprints.add(pageFingerprint)) {
                log.warn("供应链接口忽略分页参数，检测到重复页并停止继续读取：path={}, page={}", path, page);
                break;
            }
            List<Map<String, Object>> newRows = rows.stream()
                    .filter(row -> seenRows.add(row.toString()))
                    .toList();
            if (newRows.isEmpty()) {
                log.warn("供应链接口没有返回新记录，停止继续翻页：path={}, page={}", path, page);
                break;
            }
            all.addAll(newRows);
            long total = extractTotal(response);
            if ((total >= 0 && all.size() >= total) || rows.size() < pageSize) {
                break;
            }
            page++;
        }
        if (page > 100) {
            throw new IllegalStateException("供应链接口分页超过 100 页，已拒绝使用可能不完整的数据：" + path);
        }
        return all;
    }

    private JsonNode get(String path, Map<String, Object> params, Object... pathVariables) {
        try {
            return executeGet(path, params, pathVariables);
        } catch (HttpClientErrorException.Unauthorized exception) {
            connectorSessions.requireLogin(WorkspaceContext.requireId(), ConnectorSystem.SUPPLY_CHAIN,
                    "供应链登录会话已失效", true);
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
        String browserAuthorization = connectorSessions.currentSupplyAuthorization();
        if (StringUtils.hasText(browserAuthorization)) {
            return browserAuthorization;
        }
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

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static String bearer(String token) {
        String value = token.trim();
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value : "Bearer " + value;
    }
}
