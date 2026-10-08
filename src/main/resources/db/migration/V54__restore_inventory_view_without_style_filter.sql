-- V54: 库存统一视图还原为无过滤版本（V50 原样），「有效款」过滤下沉到趋势查询。
--
-- 背景：V53 给库存视图加了 `parent_sku IN (产品库)` 过滤，结果 /api/inventory/data 从 0.68s 掉到 4.88s。
--       根因：主查询形如 `where snapshot_at = :snapshotAt`，原本走 snapshot_at 索引直取最新快照；
--             叠加 IN 子查询后优化器放弃该索引，改为全表扫描 5.5 万行。
--       实测：最新快照（business_date=2026-09-24）本身已只有产品库内的 28 款，淘汰款 0 件 ——
--             主查询天然干净，视图层过滤是多余的；
--             真正需要过滤的只有「历史趋势」（淘汰款残留在 2025-02 ~ 2026-09 的曲线里，会抬高历史值）。
--       故：视图还原为 V50 形态（性能回到 0.68s），过滤下沉到 InventoryDataService.loadTrend 的 SQL ——
--           趋势本就要扫全表做聚合，加过滤的增量成本极小。
--
-- 回滚：无需回滚（本迁移只是把视图还原成 V50 的无过滤形态）。

CREATE OR REPLACE ALGORITHM=MERGE VIEW wm_ads_inventory_unified AS
SELECT source_type,
       snapshot_at,
       sku,
       parent_sku,
       item_id,
       item_name,
       listing_status AS status,
       listing_status AS publishing_status,
       brand,
       available_units,
       wfs_available,
       non_wfs_available,
       NULL AS daily_units_sold,
       NULL AS reserved_units,
       NULL AS suggested_units,
       NULL AS surplus_units,
       NULL AS forecasted_days_supply,
       NULL AS out_of_stock_date,
       NULL AS sell_through_rate,
       NULL AS item_lifecycle,
       NULL AS inbound_units
  FROM wm_ads_lx_inventory_snapshot;
