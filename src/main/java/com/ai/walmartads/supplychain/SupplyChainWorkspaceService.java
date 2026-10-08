package com.ai.walmartads.supplychain;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SupplyChainWorkspaceService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private final NamedParameterJdbcTemplate jdbc;
    private final SupplyChainStore store;

    public SupplyChainWorkspaceService(
            NamedParameterJdbcTemplate jdbc,
            SupplyChainStore store
    ) {
        this.jdbc = jdbc;
        this.store = store;
    }

    @Transactional(readOnly = true)
    public WorkspacePayload load(Map<String, Object> syncStatus) {
        boolean live = store.hasSuccessfulSnapshot();
        List<ProductionRow> production = live ? loadProduction() : List.of();
        List<DeliveryRow> deliveries = live ? loadDeliveries() : List.of();
        return new WorkspacePayload(syncStatus, summarize(production, deliveries), production, deliveries);
    }

    private List<ProductionRow> loadProduction() {
        return jdbc.query("""
                select order_no, style_no, sku, order_date, model, open_qty, cutting_qty,
                       production_qty, ship_qty, warehouse_receive_qty, qc_pass_qty,
                       rework_qty, unfinished_qty, factory_return_date, store_name,
                       order_type, factory_name, warehouse_name, snapshot_at
                  from wm_ads_supply_production_order
                 where active = 1
                 order by factory_return_date, order_no, sku
                """, new MapSqlParameterSource(), (rs, rowNum) -> new ProductionRow(
                rs.getString("order_no"),
                rs.getString("style_no"),
                rs.getString("sku"),
                rs.getObject("order_date", LocalDate.class),
                rs.getString("model"),
                value(rs.getBigDecimal("open_qty")),
                value(rs.getBigDecimal("cutting_qty")),
                value(rs.getBigDecimal("production_qty")),
                value(rs.getBigDecimal("ship_qty")),
                value(rs.getBigDecimal("warehouse_receive_qty")),
                value(rs.getBigDecimal("qc_pass_qty")),
                value(rs.getBigDecimal("rework_qty")),
                value(rs.getBigDecimal("unfinished_qty")),
                rs.getString("factory_return_date"),
                rs.getString("store_name"),
                rs.getString("order_type"),
                rs.getString("factory_name"),
                rs.getString("warehouse_name"),
                localDateTime(rs.getTimestamp("snapshot_at")),
                "SUPPLY_CHAIN"
        ));
    }

    private List<DeliveryRow> loadDeliveries() {
        return jdbc.query("""
                select delivery_no, order_no, style_no, sku, cutting_no, model,
                       order_qty, ship_qty, warehouse_receive_qty, qc_receive_qty,
                       qc_pass_qty, rework_qty, delivery_status, plan_flag,
                       logistics_complete, assigned_at, source_created_at,
                       factory_name, warehouse_name, snapshot_at
                  from wm_ads_supply_delivery_detail
                 where active = 1
                 order by source_created_at desc, delivery_no, sku
                """, new MapSqlParameterSource(), (rs, rowNum) -> new DeliveryRow(
                rs.getString("delivery_no"),
                rs.getString("order_no"),
                rs.getString("style_no"),
                rs.getString("sku"),
                rs.getString("cutting_no"),
                rs.getString("model"),
                value(rs.getBigDecimal("order_qty")),
                value(rs.getBigDecimal("ship_qty")),
                value(rs.getBigDecimal("warehouse_receive_qty")),
                value(rs.getBigDecimal("qc_receive_qty")),
                value(rs.getBigDecimal("qc_pass_qty")),
                value(rs.getBigDecimal("rework_qty")),
                rs.getString("delivery_status"),
                rs.getString("plan_flag"),
                booleanValue(rs.getObject("logistics_complete")),
                localDateTime(rs.getTimestamp("assigned_at")),
                localDateTime(rs.getTimestamp("source_created_at")),
                rs.getString("factory_name"),
                rs.getString("warehouse_name"),
                localDateTime(rs.getTimestamp("snapshot_at"))
        ));
    }

    private static Summary summarize(List<ProductionRow> production, List<DeliveryRow> deliveries) {
        Set<String> orders = new LinkedHashSet<>();
        Set<String> skus = new LinkedHashSet<>();
        Set<String> deliveryNos = new LinkedHashSet<>();
        BigDecimal productionQty = ZERO;
        BigDecimal wipQty = ZERO;
        BigDecimal shippedQty = ZERO;
        BigDecimal receivedQty = ZERO;
        BigDecimal qcPassQty = ZERO;
        BigDecimal reworkQty = ZERO;
        for (ProductionRow row : production) {
            add(orders, row.orderNo());
            add(skus, row.sku());
            productionQty = productionQty.add(row.productionQty());
            wipQty = wipQty.add(row.unfinishedQty());
        }
        for (DeliveryRow row : deliveries) {
            add(deliveryNos, row.deliveryNo());
            shippedQty = shippedQty.add(row.shipQty());
            receivedQty = receivedQty.add(row.warehouseReceiveQty());
            qcPassQty = qcPassQty.add(row.qcPassQty());
            reworkQty = reworkQty.add(row.reworkQty());
        }
        return new Summary(orders.size(), skus.size(), productionQty, wipQty,
                deliveryNos.size(), deliveries.size(), shippedQty, receivedQty,
                shippedQty.subtract(receivedQty).max(ZERO), qcPassQty, reworkQty);
    }

    private static void add(Set<String> values, String value) {
        if (value != null && !value.isBlank()) {
            values.add(value);
        }
    }

    private static BigDecimal value(BigDecimal value) {
        return value == null ? ZERO : value;
    }

    private static LocalDateTime localDateTime(Timestamp value) {
        return value == null ? null : value.toLocalDateTime();
    }

    private static Boolean booleanValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    public record WorkspacePayload(
            Map<String, Object> sync,
            Summary summary,
            List<ProductionRow> productionOrders,
            List<DeliveryRow> deliveryDetails
    ) {
    }

    public record Summary(
            int productionOrderCount,
            int productionSkuCount,
            BigDecimal productionQty,
            BigDecimal factoryWipQty,
            int deliverySheetCount,
            int deliverySkuCount,
            BigDecimal shippedQty,
            BigDecimal warehouseReceivedQty,
            BigDecimal inTransitQty,
            BigDecimal qcPassedQty,
            BigDecimal reworkQty
    ) {
    }

    public record ProductionRow(
            String orderNo,
            String styleNo,
            String sku,
            LocalDate orderDate,
            String model,
            BigDecimal openQty,
            BigDecimal cuttingQty,
            BigDecimal productionQty,
            BigDecimal shipQty,
            BigDecimal warehouseReceiveQty,
            BigDecimal qcPassQty,
            BigDecimal reworkQty,
            BigDecimal unfinishedQty,
            String factoryReturnDate,
            String storeName,
            String orderType,
            String factoryName,
            String warehouseName,
            LocalDateTime snapshotAt,
            String source
    ) {
    }

    public record DeliveryRow(
            String deliveryNo,
            String orderNo,
            String styleNo,
            String sku,
            String cuttingNo,
            String model,
            BigDecimal orderQty,
            BigDecimal shipQty,
            BigDecimal warehouseReceiveQty,
            BigDecimal qcReceiveQty,
            BigDecimal qcPassQty,
            BigDecimal reworkQty,
            String deliveryStatus,
            String planFlag,
            Boolean logisticsComplete,
            LocalDateTime assignedAt,
            LocalDateTime createdAt,
            String factoryName,
            String warehouseName,
            LocalDateTime snapshotAt
    ) {
    }
}
