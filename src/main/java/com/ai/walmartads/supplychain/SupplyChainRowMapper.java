package com.ai.walmartads.supplychain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class SupplyChainRowMapper {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private final ObjectMapper objectMapper;

    public SupplyChainRowMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<SupplyChainOrder> orders(List<Map<String, Object>> rows) {
        List<SupplyChainOrder> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String orderNo = text(row, "orderNo", "produceOrderNo");
            if (!StringUtils.hasText(orderNo)) {
                continue;
            }
            String sourceId = text(row, "id", "orderId");
            BigDecimal orderQty = decimal(row, "orderQuantitySum", "orderQuantity", "openNumber", "openQty");
            BigDecimal cuttingQty = decimal(row, "cuttingNumber", "cuttingQty");
            BigDecimal shipQty = decimal(row, "sendNumber", "outboundCount", "shipQty");
            BigDecimal warehouseReceiveQty = decimal(row, "warehouseNumber", "warehouseReceiveQty");
            BigDecimal qcPassQty = decimal(row, "currentReceipts", "harvestNumber", "qiQualifiedNumber", "qcPassQty");
            BigDecimal unfinishedQty = decimal(row, "unCurrentReceipts", "unHarvestNumber", "unfinishedQty");
            if (unfinishedQty.signum() == 0 && orderQty.compareTo(qcPassQty) > 0) {
                unfinishedQty = orderQty.subtract(qcPassQty);
            }
            result.add(new SupplyChainOrder(
                    sourceKey("order", sourceId, orderNo),
                    sourceId,
                    orderNo,
                    text(row, "orderStatus", "status"),
                    text(row, "styleNumber", "styleNo"),
                    text(row, "orderType"),
                    text(row, "factoryName", "factory"),
                    text(row, "warehouseName", "warehouse"),
                    text(row, "storeName", "store"),
                    orderQty,
                    cuttingQty,
                    shipQty,
                    warehouseReceiveQty,
                    qcPassQty,
                    unfinishedQty.max(ZERO),
                    dateTime(row, "placeOrderTime", "orderDate", "createTime"),
                    dateTime(row, "assignTime", "assignedAt"),
                    text(row, "factoryConfirmedReturnDate", "factoryReturnDate"),
                    dateTime(row, "completeTime", "completedAt"),
                    json(row)
            ));
        }
        return result;
    }

    public List<SupplyChainLogisticsRegister> logisticsRegisters(List<Map<String, Object>> rows) {
        List<SupplyChainLogisticsRegister> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String sourceId = text(row, "id", "registerId");
            String fbaNumber = text(row, "fbaNumber", "shipmentId");
            String logisticsNo = text(row, "logisticsNo", "trackingNo");
            String batchNumber = text(row, "batchNumber");
            String takeGoodsNo = text(row, "takeGoodsNo");
            if (!StringUtils.hasText(sourceId) && !StringUtils.hasText(fbaNumber)
                    && !StringUtils.hasText(logisticsNo) && !StringUtils.hasText(takeGoodsNo)) {
                continue;
            }
            result.add(new SupplyChainLogisticsRegister(
                    sourceKey("logistics", sourceId, fbaNumber, batchNumber, takeGoodsNo, logisticsNo),
                    sourceId,
                    fbaNumber,
                    text(row, "accountNumber", "account"),
                    text(row, "deliveryWarehouseName", "deliveryWarehouse"),
                    text(row, "freightForwardingName", "freightForwarding"),
                    text(row, "destination", "destinationName"),
                    text(row, "logisticsChannelsTypeName", "logisticsChannelsEnumName", "channelName"),
                    text(row, "receivingWarehouse", "receivingWarehouseName"),
                    text(row, "amazonWarehouse"),
                    batchNumber,
                    takeGoodsNo,
                    logisticsNo,
                    decimal(row, "cases", "casesQty"),
                    decimal(row, "quality", "quantity", "itemQty"),
                    decimal(row, "shippingWeight"),
                    decimal(row, "chargedWeight"),
                    decimal(row, "finalOffer", "quotation"),
                    decimal(row, "logisticsAmountSum", "logisticsAmount"),
                    date(row, "shipTime", "shipDate"),
                    dateTime(row, "submitToWlsTime", "assignedAt"),
                    date(row, "transportationDate"),
                    date(row, "signingDate"),
                    text(row, "shipmentStatus"),
                    text(row, "flowStatus"),
                    text(row, "requestStatus", "reqStatus"),
                    text(row, "exception", "exceptionRemark"),
                    json(row)
            ));
        }
        return result;
    }

    public List<SupplyChainDeliveryDetail> deliveries(List<Map<String, Object>> rows) {
        List<SupplyChainDeliveryDetail> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String sku = text(row, "stockKeepingUnit", "sku", "SKU");
            String deliveryNo = text(row, "deliverGoodsNo", "deliveryNo");
            if (!StringUtils.hasText(sku) || !StringUtils.hasText(deliveryNo)) {
                continue;
            }
            String sourceId = text(row, "detailId", "deliverGoodsDetailId", "id");
            String orderNo = text(row, "orderNo");
            String cuttingNo = text(row, "cuttingNo", "cuttingNumber");
            String model = text(row, "model", "specification");
            result.add(new SupplyChainDeliveryDetail(
                    sourceKey("delivery", sourceId, deliveryNo, orderNo, sku, cuttingNo, model),
                    sourceId,
                    deliveryNo,
                    orderNo,
                    text(row, "styleNumber", "styleNo"),
                    sku,
                    cuttingNo,
                    model,
                    decimal(row, "orderQuantity", "orderQty"),
                    decimal(row, "sendNumber", "deliverGoodsNumber", "shipQty"),
                    // 上游发货单明细的仓库收货字段是 whHarvestNumber；实测该字段长期为 0，
                    // 真正的到仓数量在 qiHarvestNumber（质检收货），在途口径以它为准。
                    decimal(row, "whHarvestNumber", "warehouseReceiveNumber", "warehouseReceiveQty", "harvestNumber"),
                    decimal(row, "qiHarvestNumber", "qcReceiveNumber"),
                    decimal(row, "qiQualifiedNumber", "qualifiedNumber", "qcPassQty"),
                    decimal(row, "reworkNumber", "reworkQty"),
                    text(row, "deliverGoodsStatus", "deliveryStatus"),
                    text(row, "planFlag"),
                    bool(row, "logisticsVoucherIsComplete", "logisticsComplete"),
                    dateTime(row, "assignTime", "assignedAt"),
                    dateTime(row, "createTime", "createdAt"),
                    text(row, "factory", "factoryName"),
                    text(row, "warehouseName", "warehouse"),
                    json(row)
            ));
        }
        return result;
    }

    public List<SupplyChainProductionOrder> productionOrders(
            List<Map<String, Object>> rows,
            List<SupplyChainDeliveryDetail> deliveries
    ) {
        Map<String, DeliveryTotals> deliveryByOrderSku = deliveryTotals(deliveries);
        List<SupplyChainProductionOrder> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String sku = text(row, "stockKeepingUnit", "sku", "SKU");
            if (!StringUtils.hasText(sku)) {
                continue;
            }
            String sourceId = text(row, "detailId", "productionOrderDetailId", "id");
            String orderNo = text(row, "orderNo");
            BigDecimal open = decimal(row, "orderQuantity", "openNumber", "openQty");
            BigDecimal cutting = decimal(row, "cuttingNumber", "cuttingQty");
            BigDecimal production = cutting.signum() > 0 ? cutting : open;
            DeliveryTotals delivered = deliveryByOrderSku.get(orderSku(orderNo, sku));
            BigDecimal ship = decimal(row, "sendNumber", "outboundCount", "shipQty");
            BigDecimal warehouseReceive = decimal(row, "harvestNumber", "warehouseReceiveQty");
            BigDecimal qcPass = decimal(row, "qiQualifiedNumber", "qualifiedNumber", "qcPassQty");
            BigDecimal rework = decimal(row, "reworkNumber", "reworkQty");
            if (delivered != null) {
                ship = ship.max(delivered.shipQty());
                warehouseReceive = warehouseReceive.max(delivered.warehouseReceiveQty());
                qcPass = qcPass.max(delivered.qcPassQty());
                rework = rework.max(delivered.reworkQty());
            }
            BigDecimal unfinished = production.subtract(ship).max(ZERO);
            String model = text(row, "model", "specification");
            result.add(new SupplyChainProductionOrder(
                    sourceKey("production", sourceId, orderNo, sku, model),
                    sourceId,
                    orderNo,
                    text(row, "styleNumber", "styleNo"),
                    sku,
                    date(row, "placeOrderTime", "orderDate", "createTime"),
                    model,
                    open,
                    cutting,
                    production,
                    ship,
                    warehouseReceive,
                    qcPass,
                    rework,
                    unfinished,
                    text(row, "factoryConfirmedReturnDate", "factoryReturnDate", "lastDeliverGoodsTime"),
                    text(row, "storeName", "store"),
                    text(row, "orderType"),
                    text(row, "factory", "factoryName"),
                    text(row, "warehouse", "warehouseName"),
                    json(row)
            ));
        }
        return result;
    }

    private static Map<String, DeliveryTotals> deliveryTotals(List<SupplyChainDeliveryDetail> rows) {
        Map<String, DeliveryTotals> totals = new LinkedHashMap<>();
        for (SupplyChainDeliveryDetail row : rows) {
            String key = orderSku(row.orderNo(), row.sku());
            totals.merge(key,
                    new DeliveryTotals(row.shipQty(), row.warehouseReceiveQty(), row.qcPassQty(), row.reworkQty()),
                    DeliveryTotals::add);
        }
        return totals;
    }

    private String json(Map<String, Object> row) {
        try {
            return objectMapper.writeValueAsString(row);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("供应链原始数据无法序列化", exception);
        }
    }

    private static String sourceKey(String type, String sourceId, String... identity) {
        StringBuilder value = new StringBuilder(type).append('|');
        if (StringUtils.hasText(sourceId)) {
            value.append("id:").append(sourceId.trim());
        } else {
            for (String part : identity) {
                value.append(part == null ? "" : part.trim()).append('|');
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境缺少 SHA-256", exception);
        }
    }

    private static String orderSku(String orderNo, String sku) {
        return normalize(orderNo) + '|' + normalize(sku);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String text(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key);
            if (value != null) {
                String text = String.valueOf(value).trim();
                if (!text.isBlank() && !"null".equalsIgnoreCase(text)) {
                    return text;
                }
            }
        }
        return null;
    }

    private static BigDecimal decimal(Map<String, Object> row, String... keys) {
        String value = text(row, keys);
        if (!StringUtils.hasText(value)) {
            return ZERO;
        }
        try {
            return new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException ignored) {
            return ZERO;
        }
    }

    private static Boolean bool(Map<String, Object> row, String... keys) {
        String value = text(row, keys);
        if (value == null) {
            return null;
        }
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value)
                || "是".equals(value) || "已完成".equals(value);
    }

    private static LocalDate date(Map<String, Object> row, String... keys) {
        String value = text(row, keys);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.substring(0, Math.min(value.length(), 10)));
        } catch (DateTimeParseException | IndexOutOfBoundsException ignored) {
            return null;
        }
    }

    private static LocalDateTime dateTime(Map<String, Object> row, String... keys) {
        String value = text(row, keys);
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().replace(' ', 'T');
        try {
            return LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(normalized.substring(0, 10)).atStartOfDay();
            } catch (DateTimeParseException | IndexOutOfBoundsException second) {
                return null;
            }
        }
    }

    private record DeliveryTotals(
            BigDecimal shipQty,
            BigDecimal warehouseReceiveQty,
            BigDecimal qcPassQty,
            BigDecimal reworkQty
    ) {
        private DeliveryTotals add(DeliveryTotals other) {
            return new DeliveryTotals(
                    shipQty.add(other.shipQty),
                    warehouseReceiveQty.add(other.warehouseReceiveQty),
                    qcPassQty.add(other.qcPassQty),
                    reworkQty.add(other.reworkQty)
            );
        }
    }
}
