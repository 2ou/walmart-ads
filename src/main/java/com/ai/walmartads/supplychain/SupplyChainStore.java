package com.ai.walmartads.supplychain;

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
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update("""
                insert into wm_ads_supply_chain_sync_log(started_at, status, triggered_by)
                values (:startedAt, 'RUNNING', :triggeredBy)
                """, new MapSqlParameterSource()
                .addValue("startedAt", LocalDateTime.now())
                .addValue("triggeredBy", triggeredBy), key, new String[]{"id"});
        if (key.getKey() == null) {
            throw new IllegalStateException("无法创建供应链同步运行记录");
        }
        return key.getKey().longValue();
    }

    @Transactional
    public void replaceSnapshot(
            List<SupplyChainProductionOrder> production,
            List<SupplyChainDeliveryDetail> deliveries,
            LocalDateTime snapshotAt
    ) {
        jdbc.update("update wm_ads_supply_production_order set active = 0 where active = 1",
                new MapSqlParameterSource());
        jdbc.update("update wm_ads_supply_delivery_detail set active = 0 where active = 1",
                new MapSqlParameterSource());
        upsertProduction(production, snapshotAt);
        upsertDeliveries(deliveries, snapshotAt);
    }

    public void finishRun(
            long runId,
            String status,
            int productionCount,
            int deliverySheetCount,
            int deliveryDetailCount,
            long durationMs,
            String errorMessage
    ) {
        jdbc.update("""
                update wm_ads_supply_chain_sync_log
                   set finished_at = :finishedAt,
                       status = :status,
                       production_count = :productionCount,
                       delivery_sheet_count = :deliverySheetCount,
                       delivery_detail_count = :deliveryDetailCount,
                       duration_ms = :durationMs,
                       error_message = :errorMessage
                 where id = :id
                """, new MapSqlParameterSource()
                .addValue("finishedAt", LocalDateTime.now())
                .addValue("status", status)
                .addValue("productionCount", productionCount)
                .addValue("deliverySheetCount", deliverySheetCount)
                .addValue("deliveryDetailCount", deliveryDetailCount)
                .addValue("durationMs", durationMs)
                .addValue("errorMessage", errorMessage)
                .addValue("id", runId));
    }

    public boolean hasSuccessfulSnapshot() {
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from wm_ads_supply_chain_sync_log
                 where status = 'SUCCESS'
                """, new MapSqlParameterSource(), Integer.class);
        return count != null && count > 0;
    }

    public Map<String, Object> latestRun() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, started_at as startedAt, finished_at as finishedAt, status,
                       triggered_by as triggeredBy, production_count as productionCount,
                       delivery_sheet_count as deliverySheetCount,
                       delivery_detail_count as deliveryDetailCount,
                       duration_ms as durationMs, error_message as errorMessage
                  from wm_ads_supply_chain_sync_log
                 order by id desc
                 limit 1
                """, new MapSqlParameterSource());
        return rows.isEmpty() ? Map.of() : new LinkedHashMap<>(rows.getFirst());
    }

    private void upsertProduction(List<SupplyChainProductionOrder> rows, LocalDateTime snapshotAt) {
        if (rows.isEmpty()) {
            return;
        }
        String sql = """
                insert into wm_ads_supply_production_order(
                    source_key, source_id, order_no, style_no, sku, order_date, model,
                    open_qty, cutting_qty, production_qty, ship_qty, warehouse_receive_qty,
                    qc_pass_qty, rework_qty, unfinished_qty, factory_return_date, store_name,
                    order_type, factory_name, warehouse_name, active, snapshot_at, raw_payload
                ) values (
                    :sourceKey, :sourceId, :orderNo, :styleNo, :sku, :orderDate, :model,
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

    private void upsertDeliveries(List<SupplyChainDeliveryDetail> rows, LocalDateTime snapshotAt) {
        if (rows.isEmpty()) {
            return;
        }
        String sql = """
                insert into wm_ads_supply_delivery_detail(
                    source_key, source_id, delivery_no, order_no, style_no, sku, cutting_no, model,
                    order_qty, ship_qty, warehouse_receive_qty, qc_receive_qty, qc_pass_qty, rework_qty,
                    delivery_status, plan_flag, logistics_complete, assigned_at, source_created_at,
                    factory_name, warehouse_name, active, snapshot_at, raw_payload
                ) values (
                    :sourceKey, :sourceId, :deliveryNo, :orderNo, :styleNo, :sku, :cuttingNo, :model,
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
}
