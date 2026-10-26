package com.ai.walmartads.supplychain;

import com.ai.walmartads.workspace.WorkspaceContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class SupplyChainStore {

    private final NamedParameterJdbcTemplate jdbc;

    public SupplyChainStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long startRun(String triggeredBy) {
        long workspaceId = WorkspaceContext.requireId();
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
                insert into wm_ads_supply_chain_sync_log(workspace_id, started_at, status, triggered_by)
                values (:workspaceId, :startedAt, 'RUNNING', :triggeredBy)
                """, new MapSqlParameterSource()
                .addValue("workspaceId", workspaceId)
                .addValue("startedAt", LocalDateTime.now())
                .addValue("triggeredBy", triggeredBy), key, new String[]{"id"});
        if (key.getKey() == null) {
            throw new IllegalStateException("无法创建供应链同步运行记录");
        }
        return key.getKey().longValue();
    }

    @Transactional
    public void replaceSnapshot(
            List<SupplyChainOrder> orders,
            List<SupplyChainProductionOrder> production,
            List<SupplyChainDeliveryDetail> deliveries,
            List<SupplyChainLogisticsRegister> logistics,
            LocalDateTime snapshotAt
    ) {
        long workspaceId = WorkspaceContext.requireId();
        MapSqlParameterSource owner = new MapSqlParameterSource("workspaceId", workspaceId);
        jdbc.update("update wm_ads_supply_order set active = 0 where workspace_id=:workspaceId and active = 1", owner);
        jdbc.update("update wm_ads_supply_production_order set active = 0 where workspace_id=:workspaceId and active = 1", owner);
        jdbc.update("update wm_ads_supply_delivery_detail set active = 0 where workspace_id=:workspaceId and active = 1", owner);
        jdbc.update("update wm_ads_supply_logistics_register set active = 0 where workspace_id=:workspaceId and active = 1", owner);
        upsertOrders(workspaceId, orders, snapshotAt);
        upsertProduction(workspaceId, production, snapshotAt);
        upsertDeliveries(workspaceId, deliveries, snapshotAt);
        upsertLogistics(workspaceId, logistics, snapshotAt);
    }

    public void finishRun(
            long runId,
            String status,
            int orderCount,
            int productionCount,
            int deliverySheetCount,
            int deliveryDetailCount,
            int logisticsCount,
            long durationMs,
            String errorMessage
    ) {
        jdbc.update("""
                update wm_ads_supply_chain_sync_log
                   set finished_at = :finishedAt,
                       status = :status,
                       order_count = :orderCount,
                       production_count = :productionCount,
                       delivery_sheet_count = :deliverySheetCount,
                       delivery_detail_count = :deliveryDetailCount,
                       logistics_count = :logisticsCount,
                       duration_ms = :durationMs,
                       error_message = :errorMessage
                 where id = :id
                """, new MapSqlParameterSource()
                .addValue("finishedAt", LocalDateTime.now())
                .addValue("status", status)
                .addValue("orderCount", orderCount)
                .addValue("productionCount", productionCount)
                .addValue("deliverySheetCount", deliverySheetCount)
                .addValue("deliveryDetailCount", deliveryDetailCount)
                .addValue("logisticsCount", logisticsCount)
                .addValue("durationMs", durationMs)
                .addValue("errorMessage", errorMessage)
                .addValue("id", runId));
    }

    public boolean hasSuccessfulSnapshot() {
        long workspaceId = WorkspaceContext.requireId();
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from wm_ads_supply_chain_sync_log
                 where workspace_id=:workspaceId and status = 'SUCCESS'
                """, new MapSqlParameterSource("workspaceId", workspaceId), Integer.class);
        return count != null && count > 0;
    }

    /** 最近一次成功同步的完成时间；从未成功过则返回 null。 */
    public LocalDateTime lastSuccessAt() {
        long workspaceId = WorkspaceContext.requireId();
        return jdbc.query("""
                        select max(finished_at) as last_success_at
                          from wm_ads_supply_chain_sync_log
                         where workspace_id=:workspaceId and status = 'SUCCESS'
                        """, new MapSqlParameterSource("workspaceId", workspaceId),
                rs -> rs.next() && rs.getTimestamp("last_success_at") != null
                        ? rs.getTimestamp("last_success_at").toLocalDateTime()
                        : null);
    }

    public Map<String, Object> latestRun() {
        long workspaceId = WorkspaceContext.requireId();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, started_at as startedAt, finished_at as finishedAt, status,
                       triggered_by as triggeredBy, order_count as orderCount,
                       production_count as productionCount,
                       delivery_sheet_count as deliverySheetCount,
                       delivery_detail_count as deliveryDetailCount,
                       logistics_count as logisticsCount,
                       duration_ms as durationMs, error_message as errorMessage
                  from wm_ads_supply_chain_sync_log
                 where workspace_id=:workspaceId
                 order by id desc
                 limit 1
                """, new MapSqlParameterSource("workspaceId", workspaceId));
        return rows.isEmpty() ? Map.of() : new LinkedHashMap<>(rows.getFirst());
    }

    public int recoverAbandonedRuns() {
        return jdbc.update("""
                update wm_ads_supply_chain_sync_log
                   set status='FAILED', finished_at=now(6),
                       duration_ms=timestampdiff(microsecond,started_at,now(6))/1000,
                       error_message=coalesce(error_message,'服务重启，上一轮供应链同步已中断')
                 where status='RUNNING'
                """, new MapSqlParameterSource());
    }

    private void upsertOrders(long workspaceId, List<SupplyChainOrder> rows, LocalDateTime snapshotAt) {
        if (rows.isEmpty()) {
            return;
        }
        String sql = """
                insert into wm_ads_supply_order(
                    workspace_id, source_key, source_id, order_no, order_status, style_no, order_type,
                    factory_name, warehouse_name, store_name, order_qty, cutting_qty,
                    ship_qty, warehouse_receive_qty, qc_pass_qty, unfinished_qty,
                    placed_at, assigned_at, factory_return_date, completed_at,
                    active, snapshot_at, raw_payload
                ) values (
                    :workspaceId, :sourceKey, :sourceId, :orderNo, :orderStatus, :styleNo, :orderType,
                    :factoryName, :warehouseName, :storeName, :orderQty, :cuttingQty,
                    :shipQty, :warehouseReceiveQty, :qcPassQty, :unfinishedQty,
                    :placedAt, :assignedAt, :factoryReturnDate, :completedAt,
                    1, :snapshotAt, :rawPayload
                ) on duplicate key update
                    source_id = values(source_id), order_no = values(order_no),
                    order_status = values(order_status), style_no = values(style_no),
                    order_type = values(order_type), factory_name = values(factory_name),
                    warehouse_name = values(warehouse_name), store_name = values(store_name),
                    order_qty = values(order_qty), cutting_qty = values(cutting_qty),
                    ship_qty = values(ship_qty), warehouse_receive_qty = values(warehouse_receive_qty),
                    qc_pass_qty = values(qc_pass_qty), unfinished_qty = values(unfinished_qty),
                    placed_at = values(placed_at), assigned_at = values(assigned_at),
                    factory_return_date = values(factory_return_date), completed_at = values(completed_at),
                    active = 1, snapshot_at = values(snapshot_at), raw_payload = values(raw_payload)
                """;
        MapSqlParameterSource[] batch = rows.stream().map(row -> new MapSqlParameterSource()
                .addValue("workspaceId", workspaceId)
                .addValue("sourceKey", row.sourceKey())
                .addValue("sourceId", row.sourceId())
                .addValue("orderNo", row.orderNo())
                .addValue("orderStatus", row.orderStatus())
                .addValue("styleNo", row.styleNo())
                .addValue("orderType", row.orderType())
                .addValue("factoryName", row.factoryName())
                .addValue("warehouseName", row.warehouseName())
                .addValue("storeName", row.storeName())
                .addValue("orderQty", row.orderQty())
                .addValue("cuttingQty", row.cuttingQty())
                .addValue("shipQty", row.shipQty())
                .addValue("warehouseReceiveQty", row.warehouseReceiveQty())
                .addValue("qcPassQty", row.qcPassQty())
                .addValue("unfinishedQty", row.unfinishedQty())
                .addValue("placedAt", row.placedAt())
                .addValue("assignedAt", row.assignedAt())
                .addValue("factoryReturnDate", row.factoryReturnDate())
                .addValue("completedAt", row.completedAt())
                .addValue("snapshotAt", snapshotAt)
                .addValue("rawPayload", row.rawPayload())).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(sql, batch);
    }

    private void upsertProduction(long workspaceId, List<SupplyChainProductionOrder> rows, LocalDateTime snapshotAt) {
        if (rows.isEmpty()) {
            return;
        }
        String sql = """
                insert into wm_ads_supply_production_order(
                    workspace_id, source_key, source_id, order_no, style_no, sku, order_date, model,
                    open_qty, cutting_qty, production_qty, ship_qty, warehouse_receive_qty,
                    qc_pass_qty, rework_qty, unfinished_qty, factory_return_date, store_name,
                    order_type, factory_name, warehouse_name, active, snapshot_at, raw_payload
                ) values (
                    :workspaceId, :sourceKey, :sourceId, :orderNo, :styleNo, :sku, :orderDate, :model,
                    :openQty, :cuttingQty, :productionQty, :shipQty, :warehouseReceiveQty,
                    :qcPassQty, :reworkQty, :unfinishedQty, :factoryReturnDate, :storeName,
                    :orderType, :factoryName, :warehouseName, 1, :snapshotAt, :rawPayload
                ) on duplicate key update
                    source_id = values(source_id), order_no = values(order_no), style_no = values(style_no),
                    sku = values(sku), order_date = values(order_date), model = values(model),
                    open_qty = values(open_qty), cutting_qty = values(cutting_qty),
                    production_qty = values(production_qty), ship_qty = values(ship_qty),
                    warehouse_receive_qty = values(warehouse_receive_qty), qc_pass_qty = values(qc_pass_qty),
                    rework_qty = values(rework_qty), unfinished_qty = values(unfinished_qty),
                    factory_return_date = values(factory_return_date), store_name = values(store_name),
                    order_type = values(order_type), factory_name = values(factory_name),
                    warehouse_name = values(warehouse_name), active = 1,
                    snapshot_at = values(snapshot_at), raw_payload = values(raw_payload)
                """;
        MapSqlParameterSource[] batch = rows.stream().map(row -> new MapSqlParameterSource()
                .addValue("workspaceId", workspaceId)
                .addValue("sourceKey", row.sourceKey())
                .addValue("sourceId", row.sourceId())
                .addValue("orderNo", row.orderNo())
                .addValue("styleNo", row.styleNo())
                .addValue("sku", row.sku())
                .addValue("orderDate", row.orderDate())
                .addValue("model", row.model())
                .addValue("openQty", row.openQty())
                .addValue("cuttingQty", row.cuttingQty())
                .addValue("productionQty", row.productionQty())
                .addValue("shipQty", row.shipQty())
                .addValue("warehouseReceiveQty", row.warehouseReceiveQty())
                .addValue("qcPassQty", row.qcPassQty())
                .addValue("reworkQty", row.reworkQty())
                .addValue("unfinishedQty", row.unfinishedQty())
                .addValue("factoryReturnDate", row.factoryReturnDate())
                .addValue("storeName", row.storeName())
                .addValue("orderType", row.orderType())
                .addValue("factoryName", row.factoryName())
                .addValue("warehouseName", row.warehouseName())
                .addValue("snapshotAt", snapshotAt)
                .addValue("rawPayload", row.rawPayload())).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(sql, batch);
    }

    private void upsertDeliveries(long workspaceId, List<SupplyChainDeliveryDetail> rows, LocalDateTime snapshotAt) {
        if (rows.isEmpty()) {
            return;
        }
        String sql = """
                insert into wm_ads_supply_delivery_detail(
                    workspace_id, source_key, source_id, delivery_no, order_no, style_no, sku, cutting_no, model,
                    order_qty, ship_qty, warehouse_receive_qty, qc_receive_qty, qc_pass_qty, rework_qty,
                    delivery_status, plan_flag, logistics_complete, assigned_at, source_created_at,
                    factory_name, warehouse_name, active, snapshot_at, raw_payload
                ) values (
                    :workspaceId, :sourceKey, :sourceId, :deliveryNo, :orderNo, :styleNo, :sku, :cuttingNo, :model,
                    :orderQty, :shipQty, :warehouseReceiveQty, :qcReceiveQty, :qcPassQty, :reworkQty,
                    :deliveryStatus, :planFlag, :logisticsComplete, :assignedAt, :sourceCreatedAt,
                    :factoryName, :warehouseName, 1, :snapshotAt, :rawPayload
                ) on duplicate key update
                    source_id = values(source_id), delivery_no = values(delivery_no), order_no = values(order_no),
                    style_no = values(style_no), sku = values(sku), cutting_no = values(cutting_no), model = values(model),
                    order_qty = values(order_qty), ship_qty = values(ship_qty),
                    warehouse_receive_qty = values(warehouse_receive_qty), qc_receive_qty = values(qc_receive_qty),
                    qc_pass_qty = values(qc_pass_qty), rework_qty = values(rework_qty),
                    delivery_status = values(delivery_status), plan_flag = values(plan_flag),
                    logistics_complete = values(logistics_complete), assigned_at = values(assigned_at),
                    source_created_at = values(source_created_at), factory_name = values(factory_name),
                    warehouse_name = values(warehouse_name), active = 1,
                    snapshot_at = values(snapshot_at), raw_payload = values(raw_payload)
                """;
        MapSqlParameterSource[] batch = rows.stream().map(row -> new MapSqlParameterSource()
                .addValue("workspaceId", workspaceId)
                .addValue("sourceKey", row.sourceKey())
                .addValue("sourceId", row.sourceId())
                .addValue("deliveryNo", row.deliveryNo())
                .addValue("orderNo", row.orderNo())
                .addValue("styleNo", row.styleNo())
                .addValue("sku", row.sku())
                .addValue("cuttingNo", row.cuttingNo())
                .addValue("model", row.model())
                .addValue("orderQty", row.orderQty())
                .addValue("shipQty", row.shipQty())
                .addValue("warehouseReceiveQty", row.warehouseReceiveQty())
                .addValue("qcReceiveQty", row.qcReceiveQty())
                .addValue("qcPassQty", row.qcPassQty())
                .addValue("reworkQty", row.reworkQty())
                .addValue("deliveryStatus", row.deliveryStatus())
                .addValue("planFlag", row.planFlag())
                .addValue("logisticsComplete", row.logisticsComplete())
                .addValue("assignedAt", row.assignedAt())
                .addValue("sourceCreatedAt", row.sourceCreatedAt())
                .addValue("factoryName", row.factoryName())
                .addValue("warehouseName", row.warehouseName())
                .addValue("snapshotAt", snapshotAt)
                .addValue("rawPayload", row.rawPayload())).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(sql, batch);
    }

    private void upsertLogistics(long workspaceId, List<SupplyChainLogisticsRegister> rows, LocalDateTime snapshotAt) {
        if (rows.isEmpty()) {
            return;
        }
        String sql = """
                insert into wm_ads_supply_logistics_register(
                    workspace_id, source_key, source_id, fba_number, account_number, delivery_warehouse_name,
                    freight_forwarding_name, destination, channel_name, receiving_warehouse,
                    amazon_warehouse, batch_number, take_goods_no, logistics_no, cases_qty,
                    item_qty, shipping_weight, charged_weight, final_offer, logistics_amount,
                    ship_date, assigned_at, transportation_date, signing_date, shipment_status,
                    flow_status, request_status, exception_note, active, snapshot_at, raw_payload
                ) values (
                    :workspaceId, :sourceKey, :sourceId, :fbaNumber, :accountNumber, :deliveryWarehouseName,
                    :freightForwardingName, :destination, :channelName, :receivingWarehouse,
                    :amazonWarehouse, :batchNumber, :takeGoodsNo, :logisticsNo, :casesQty,
                    :itemQty, :shippingWeight, :chargedWeight, :finalOffer, :logisticsAmount,
                    :shipDate, :assignedAt, :transportationDate, :signingDate, :shipmentStatus,
                    :flowStatus, :requestStatus, :exception, 1, :snapshotAt, :rawPayload
                ) on duplicate key update
                    source_id = values(source_id), fba_number = values(fba_number),
                    account_number = values(account_number), delivery_warehouse_name = values(delivery_warehouse_name),
                    freight_forwarding_name = values(freight_forwarding_name), destination = values(destination),
                    channel_name = values(channel_name), receiving_warehouse = values(receiving_warehouse),
                    amazon_warehouse = values(amazon_warehouse), batch_number = values(batch_number),
                    take_goods_no = values(take_goods_no), logistics_no = values(logistics_no),
                    cases_qty = values(cases_qty), item_qty = values(item_qty),
                    shipping_weight = values(shipping_weight), charged_weight = values(charged_weight),
                    final_offer = values(final_offer), logistics_amount = values(logistics_amount),
                    ship_date = values(ship_date), assigned_at = values(assigned_at),
                    transportation_date = values(transportation_date), signing_date = values(signing_date),
                    shipment_status = values(shipment_status), flow_status = values(flow_status),
                    request_status = values(request_status), exception_note = values(exception_note),
                    active = 1, snapshot_at = values(snapshot_at), raw_payload = values(raw_payload)
                """;
        MapSqlParameterSource[] batch = rows.stream().map(row -> new MapSqlParameterSource()
                .addValue("workspaceId", workspaceId)
                .addValue("sourceKey", row.sourceKey())
                .addValue("sourceId", row.sourceId())
                .addValue("fbaNumber", row.fbaNumber())
                .addValue("accountNumber", row.accountNumber())
                .addValue("deliveryWarehouseName", row.deliveryWarehouseName())
                .addValue("freightForwardingName", row.freightForwardingName())
                .addValue("destination", row.destination())
                .addValue("channelName", row.channelName())
                .addValue("receivingWarehouse", row.receivingWarehouse())
                .addValue("amazonWarehouse", row.amazonWarehouse())
                .addValue("batchNumber", row.batchNumber())
                .addValue("takeGoodsNo", row.takeGoodsNo())
                .addValue("logisticsNo", row.logisticsNo())
                .addValue("casesQty", row.casesQty())
                .addValue("itemQty", row.itemQty())
                .addValue("shippingWeight", row.shippingWeight())
                .addValue("chargedWeight", row.chargedWeight())
                .addValue("finalOffer", row.finalOffer())
                .addValue("logisticsAmount", row.logisticsAmount())
                .addValue("shipDate", row.shipDate())
                .addValue("assignedAt", row.assignedAt())
                .addValue("transportationDate", row.transportationDate())
                .addValue("signingDate", row.signingDate())
                .addValue("shipmentStatus", row.shipmentStatus())
                .addValue("flowStatus", row.flowStatus())
                .addValue("requestStatus", row.requestStatus())
                .addValue("exception", row.exception())
                .addValue("snapshotAt", snapshotAt)
                .addValue("rawPayload", row.rawPayload())).toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(sql, batch);
    }
}
