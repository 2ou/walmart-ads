package com.ai.walmartads.supplychain;

import com.ai.walmartads.inventory.application.InventoryDataService;
import com.ai.walmartads.workspace.WorkspaceContext;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SupplyChainWorkspaceService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;
    private static final long CACHE_TTL_NANOS = Duration.ofMinutes(2).toNanos();
    private final NamedParameterJdbcTemplate jdbc;
    private final SupplyChainStore store;
    private final InventoryDataService inventory;
    private final Map<Long, SnapshotCacheEntry> snapshotCache = new ConcurrentHashMap<>();

    private record SnapshotCacheEntry(
            LocalDateTime lastSuccessAt,
            long expiresAtNanos,
            Summary summary,
            List<OrderRow> orders,
            List<ProductionRow> production,
            List<DeliveryRow> deliveries,
            List<LogisticsRow> logistics,
            List<WorkbenchProductionRow> workbench
    ) {
    }

    public SupplyChainWorkspaceService(
            NamedParameterJdbcTemplate jdbc,
            SupplyChainStore store,
            InventoryDataService inventory
    ) {
        this.jdbc = jdbc;
        this.store = store;
        this.inventory = inventory;
    }

    @EventListener
    public void onSupplyChainSynced(SupplyChainSyncedEvent event) {
        WorkspaceContext.current()
                .map(com.ai.walmartads.workspace.WorkspacePrincipal::workspaceId)
                .ifPresentOrElse(snapshotCache::remove, snapshotCache::clear);
    }

    @Transactional(readOnly = true)
    public WorkspacePayload load(Map<String, Object> syncStatus) {
        long workspaceId = WorkspaceContext.requireId();
        LocalDateTime lastSuccessAt = store.lastSuccessAt();
        boolean live = lastSuccessAt != null || store.hasSuccessfulSnapshot();
        long now = System.nanoTime();
        SnapshotCacheEntry cached = snapshotCache.get(workspaceId);
        if (cached != null
                && cached.expiresAtNanos() > now
                && Objects.equals(cached.lastSuccessAt(), lastSuccessAt)) {
            return new WorkspacePayload(
                    syncStatus,
                    cached.summary(),
                    cached.orders(),
                    cached.production(),
                    cached.deliveries(),
                    cached.logistics(),
                    cached.workbench()
            );
        }
        List<OrderRow> orders = live ? loadOrders(workspaceId) : List.of();
        List<ProductionRow> production = live ? loadProduction(workspaceId) : List.of();
        List<DeliveryRow> deliveries = live ? loadDeliveries(workspaceId) : List.of();
        List<LogisticsRow> logistics = live ? loadLogistics(workspaceId) : List.of();
        // The same read model used by the inventory production detail keeps catalog matching and fallback identical.
        List<WorkbenchProductionRow> workbench = inventory.productionOrders().stream()
                .map(row -> new WorkbenchProductionRow(row.orderNo(), row.sku(), row.styleNo(), row.productionQty(),
                        row.plannedShipQty(), row.unfinishedQty(), row.inventoryQty(), row.includedInInventory(),
                        row.shipDate(), row.reconciliationStatus(), row.source()))
                .toList();
        Summary summary = summarize(orders, production, deliveries, logistics, workbench, live);
        snapshotCache.put(workspaceId, new SnapshotCacheEntry(
                lastSuccessAt, now + CACHE_TTL_NANOS, summary, orders, production, deliveries, logistics, workbench
        ));
        return new WorkspacePayload(syncStatus, summary, orders, production, deliveries, logistics, workbench);
    }

    private List<OrderRow> loadOrders(long workspaceId) {
        return jdbc.query("""
                select order_no, order_status, style_no, order_type, factory_name,
                       warehouse_name, store_name, order_qty, cutting_qty, ship_qty,
                       warehouse_receive_qty, qc_pass_qty, unfinished_qty, placed_at,
                       assigned_at, factory_return_date, completed_at, snapshot_at
                  from wm_ads_supply_order
                 where workspace_id=:workspaceId and active = 1
                 order by placed_at desc, order_no
                """, new MapSqlParameterSource("workspaceId", workspaceId), (rs, rowNum) -> new OrderRow(
                rs.getString("order_no"),
                rs.getString("order_status"),
                rs.getString("style_no"),
                rs.getString("order_type"),
                rs.getString("factory_name"),
                rs.getString("warehouse_name"),
                rs.getString("store_name"),
                value(rs.getBigDecimal("order_qty")),
                value(rs.getBigDecimal("cutting_qty")),
                value(rs.getBigDecimal("ship_qty")),
                value(rs.getBigDecimal("warehouse_receive_qty")),
                value(rs.getBigDecimal("qc_pass_qty")),
                value(rs.getBigDecimal("unfinished_qty")),
                localDateTime(rs.getTimestamp("placed_at")),
                localDateTime(rs.getTimestamp("assigned_at")),
                rs.getString("factory_return_date"),
                localDateTime(rs.getTimestamp("completed_at")),
                localDateTime(rs.getTimestamp("snapshot_at"))
        ));
    }

    private List<ProductionRow> loadProduction(long workspaceId) {
        return jdbc.query("""
                select order_no, style_no, sku, order_date, model, open_qty, cutting_qty,
                       production_qty, ship_qty, warehouse_receive_qty, qc_pass_qty,
                       rework_qty, unfinished_qty, factory_return_date, store_name,
                       order_type, factory_name, warehouse_name, snapshot_at
                  from wm_ads_supply_production_order
                 where workspace_id=:workspaceId and active = 1
                 order by factory_return_date, order_no, sku
                """, new MapSqlParameterSource("workspaceId", workspaceId), (rs, rowNum) -> new ProductionRow(
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

    private List<DeliveryRow> loadDeliveries(long workspaceId) {
        return jdbc.query("""
                select delivery_no, order_no, style_no, sku, cutting_no, model,
                       order_qty, ship_qty, warehouse_receive_qty, qc_receive_qty,
                       qc_pass_qty, rework_qty, delivery_status, plan_flag,
                       logistics_complete, assigned_at, source_created_at,
                       factory_name, warehouse_name, snapshot_at,
                       json_unquote(json_extract(raw_payload, '$.outboundOrderTime')) as outbound_time,
                       json_unquote(json_extract(raw_payload, '$.qiHarvestStatus')) as qc_harvest_status,
                       json_unquote(json_extract(raw_payload, '$.whHarvestStatus')) as warehouse_harvest_status
                  from wm_ads_supply_delivery_detail
                 where workspace_id=:workspaceId and active = 1
                 order by source_created_at desc, delivery_no, sku
                """, new MapSqlParameterSource("workspaceId", workspaceId), (rs, rowNum) -> {
            BigDecimal shipQty = value(rs.getBigDecimal("ship_qty"));
            BigDecimal qcReceived = value(rs.getBigDecimal("qc_receive_qty"));
            BigDecimal warehouseReceived = value(rs.getBigDecimal("warehouse_receive_qty"));
            BigDecimal qcPassed = value(rs.getBigDecimal("qc_pass_qty"));
            String deliveryStatus = rs.getString("delivery_status");
            String planFlag = rs.getString("plan_flag");
            LocalDateTime snapshotAt = localDateTime(rs.getTimestamp("snapshot_at"));
            var progress = SupplyChainDeliveryProgress.classify(shipQty, qcReceived, planFlag, deliveryStatus,
                    rs.getString("qc_harvest_status"), rs.getString("warehouse_harvest_status"),
                    rs.getString("outbound_time"), snapshotAt, warehouseReceived.signum() > 0 || qcPassed.signum() > 0);
            return new DeliveryRow(
                rs.getString("delivery_no"),
                rs.getString("order_no"),
                rs.getString("style_no"),
                rs.getString("sku"),
                rs.getString("cutting_no"),
                rs.getString("model"),
                value(rs.getBigDecimal("order_qty")),
                shipQty,
                warehouseReceived,
                qcReceived,
                qcPassed,
                value(rs.getBigDecimal("rework_qty")),
                deliveryStatus,
                planFlag,
                booleanValue(rs.getObject("logistics_complete")),
                localDateTime(rs.getTimestamp("assigned_at")),
                localDateTime(rs.getTimestamp("source_created_at")),
                rs.getString("factory_name"),
                rs.getString("warehouse_name"),
                snapshotAt,
                progress.stage().name(),
                progress.quantity(),
                progress.outboundAt()
            );
        });
    }

    private List<LogisticsRow> loadLogistics(long workspaceId) {
        return jdbc.query("""
                select fba_number, account_number, delivery_warehouse_name,
                       freight_forwarding_name, destination, channel_name,
                       receiving_warehouse, amazon_warehouse, batch_number,
                       take_goods_no, logistics_no, cases_qty, item_qty,
                       shipping_weight, charged_weight, final_offer, logistics_amount,
                       ship_date, assigned_at, transportation_date, signing_date,
                       shipment_status, flow_status, request_status, exception_note,
                       snapshot_at
                  from wm_ads_supply_logistics_register
                 where workspace_id=:workspaceId and active = 1
                   -- 只展示有物流信息的单：剔除「运营登记(flow_status=1)」这类刚建单、
                   -- 货代/渠道/启运/签收全空的记录；货件状态仅保留待确认(1)/已完成(2)。
                   and (flow_status is null or flow_status != '1')
                   and (shipment_status is null or shipment_status in ('1', '2'))
                 order by transportation_date desc, ship_date desc, fba_number
                """, new MapSqlParameterSource("workspaceId", workspaceId), (rs, rowNum) -> new LogisticsRow(
                rs.getString("fba_number"),
                rs.getString("account_number"),
                rs.getString("delivery_warehouse_name"),
                rs.getString("freight_forwarding_name"),
                rs.getString("destination"),
                rs.getString("channel_name"),
                rs.getString("receiving_warehouse"),
                rs.getString("amazon_warehouse"),
                rs.getString("batch_number"),
                rs.getString("take_goods_no"),
                rs.getString("logistics_no"),
                value(rs.getBigDecimal("cases_qty")),
                value(rs.getBigDecimal("item_qty")),
                value(rs.getBigDecimal("shipping_weight")),
                value(rs.getBigDecimal("charged_weight")),
                value(rs.getBigDecimal("final_offer")),
                value(rs.getBigDecimal("logistics_amount")),
                rs.getObject("ship_date", LocalDate.class),
                localDateTime(rs.getTimestamp("assigned_at")),
                rs.getObject("transportation_date", LocalDate.class),
                rs.getObject("signing_date", LocalDate.class),
                rs.getString("shipment_status"),
                rs.getString("flow_status"),
                rs.getString("request_status"),
                rs.getString("exception_note"),
                localDateTime(rs.getTimestamp("snapshot_at"))
        ));
    }

    static Summary summarize(
            List<OrderRow> orderRows,
            List<ProductionRow> production,
            List<DeliveryRow> deliveries,
            List<LogisticsRow> logistics,
            List<WorkbenchProductionRow> workbench,
            boolean successfulSupplySnapshot
    ) {
        Set<String> orders = new LinkedHashSet<>();
        Set<String> skus = new LinkedHashSet<>();
        Set<String> deliveryNos = new LinkedHashSet<>();
        BigDecimal productionQty = ZERO;
        BigDecimal wipQty = ZERO;
        BigDecimal shippedQty = ZERO;
        BigDecimal receivedQty = ZERO;
        BigDecimal qcReceivedQty = ZERO;
        BigDecimal qcPassQty = ZERO;
        BigDecimal reworkQty = ZERO;
        BigDecimal inTransitQty = ZERO;
        BigDecimal pendingPlanQty = ZERO;
        BigDecimal historicalReceiptGapQty = ZERO;
        BigDecimal unverifiedQty = ZERO;
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
            qcReceivedQty = qcReceivedQty.add(row.qcReceiveQty());
            qcPassQty = qcPassQty.add(row.qcPassQty());
            reworkQty = reworkQty.add(row.reworkQty());
            // 汇总与明细使用同一物理环节分类；某行超收不会冲抵其他行的收货差异。
            switch (SupplyChainDeliveryProgress.Stage.valueOf(row.deliveryStage())) {
                case PENDING_PLAN -> pendingPlanQty = pendingPlanQty.add(row.deliveryStageQty());
                case IN_TRANSIT -> inTransitQty = inTransitQty.add(row.deliveryStageQty());
                case HISTORICAL_RECEIPT_GAP -> historicalReceiptGapQty = historicalReceiptGapQty.add(row.deliveryStageQty());
                case UNVERIFIED -> unverifiedQty = unverifiedQty.add(row.deliveryStageQty());
                default -> { }
            }
        }
        BigDecimal matchedWip = ZERO;
        BigDecimal unmatchedWip = ZERO;
        for (WorkbenchProductionRow row : workbench) {
            if (row.includedInInventory()) matchedWip = matchedWip.add(BigDecimal.valueOf(row.inventoryQty()));
            else unmatchedWip = unmatchedWip.add(BigDecimal.valueOf(row.wipQty()));
        }
        return new Summary(orderRows.size(), orders.size(), skus.size(), productionQty, wipQty,
                deliveryNos.size(), deliveries.size(), shippedQty, receivedQty, qcReceivedQty,
                inTransitQty, qcPassQty, reworkQty, logistics.size(), pendingPlanQty, historicalReceiptGapQty,
                unverifiedQty, matchedWip, unmatchedWip, matchedWip.add(unmatchedWip),
                workbench.isEmpty() ? (successfulSupplySnapshot ? "SUPPLY_CHAIN" : null) : workbench.getFirst().source());
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
            List<OrderRow> orders,
            List<ProductionRow> productionOrders,
            List<DeliveryRow> deliveryDetails,
            List<LogisticsRow> logisticsRegisters,
            List<WorkbenchProductionRow> workbenchProductionRows
    ) {
    }

    public record Summary(
            int orderCount,
            int productionOrderCount,
            int productionSkuCount,
            BigDecimal productionQty,
            BigDecimal factoryWipQty,
            int deliverySheetCount,
            int deliverySkuCount,
            BigDecimal shippedQty,
            BigDecimal warehouseReceivedQty,
            BigDecimal qcReceivedQty,
            BigDecimal inTransitQty,
            BigDecimal qcPassedQty,
            BigDecimal reworkQty,
            int logisticsCount,
            BigDecimal pendingPlanQty,
            BigDecimal historicalReceiptGapQty,
            BigDecimal unverifiedQty,
            BigDecimal workbenchWipQty,
            BigDecimal workbenchWipUnmatchedQty,
            BigDecimal workbenchWipTotalQty,
            String workbenchWipSource
    ) {
    }

    public record OrderRow(
            String orderNo,
            String orderStatus,
            String styleNo,
            String orderType,
            String factoryName,
            String warehouseName,
            String storeName,
            BigDecimal orderQty,
            BigDecimal cuttingQty,
            BigDecimal shipQty,
            BigDecimal warehouseReceiveQty,
            BigDecimal qcPassQty,
            BigDecimal unfinishedQty,
            LocalDateTime placedAt,
            LocalDateTime assignedAt,
            String factoryReturnDate,
            LocalDateTime completedAt,
            LocalDateTime snapshotAt
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

    public record WorkbenchProductionRow(
            String orderNo,
            String sku,
            String styleNo,
            double productionQty,
            Double plannedShipQty,
            double wipQty,
            double inventoryQty,
            boolean includedInInventory,
            String factoryReturnDate,
            String reconciliationStatus,
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
            LocalDateTime snapshotAt,
            String deliveryStage,
            BigDecimal deliveryStageQty,
            LocalDateTime outboundAt
    ) {
    }

    public record LogisticsRow(
            String fbaNumber,
            String accountNumber,
            String deliveryWarehouseName,
            String freightForwardingName,
            String destination,
            String channelName,
            String receivingWarehouse,
            String amazonWarehouse,
            String batchNumber,
            String takeGoodsNo,
            String logisticsNo,
            BigDecimal casesQty,
            BigDecimal itemQty,
            BigDecimal shippingWeight,
            BigDecimal chargedWeight,
            BigDecimal finalOffer,
            BigDecimal logisticsAmount,
            LocalDate shipDate,
            LocalDateTime assignedAt,
            LocalDate transportationDate,
            LocalDate signingDate,
            String shipmentStatus,
            String flowStatus,
            String requestStatus,
            String exception,
            LocalDateTime snapshotAt
    ) {
    }
}
