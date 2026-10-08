-- V50: 将旧报表库存快照历史并入领星库存快照表，废除旧表（RENAME 归档），视图改单表投影。
--
-- 背景：V49 用 UNION ALL 视图拼接「旧表(历史) ∪ 领星表(当期)」，但 MySQL 对 UNION 视图只能用 TEMPTABLE 物化，
--       每次库存页查询都先把 5.5 万行物化进无索引临时表再做聚合，导致 /api/inventory/data 慢约 1.6s。
--       本次把旧表「可售量历史 + 维度」并入领星表（只搬 available_units，不搬 reserved/suggested 等领星永不给的列），
--       视图改为单表投影（列名与 V49 完全一致，下游 6 个消费方 SQL 零改动），旧表 RENAME 为 _deprecated_* 保留一键回滚。
--
-- 回滚步骤（确认不回头再执行，避免与领星新写入冲突）：
--   1) RENAME TABLE wm_ads_inventory_snapshot TO _deprecated_wm_ads_inventory_snapshot;  -- 即下方第 4 步的逆向
--   2) 还原视图为 V49 的 UNION 形态（旧表段 + 领星段）
--   3) ALTER TABLE wm_ads_lx_inventory_snapshot DROP COLUMN source_type;

-- 1) 领星表加 source_type 列，标记历史来源（LEGACY=旧日报表 / WEEKLY=旧周报 / LINGXING=领星在线商品）
ALTER TABLE wm_ads_lx_inventory_snapshot
    ADD COLUMN source_type VARCHAR(20) NOT NULL DEFAULT 'LINGXING' AFTER listing_status;

-- 2) 旧表历史并入领星表（可售量 + 维度；business_date = 导入日；wfs 取可售量、non_wfs 恒 0，与本账号 100% WFS 一致）
INSERT INTO wm_ads_lx_inventory_snapshot
    (business_date, snapshot_at, sku, parent_sku, item_id, item_name, brand, listing_status, available_units, wfs_available, non_wfs_available, created_at, source_type)
SELECT DATE(s.snapshot_at) AS business_date,
       s.snapshot_at AS snapshot_at,
       s.sku AS sku,
       s.parent_sku AS parent_sku,
       s.item_id AS item_id,
       s.item_name AS item_name,
       s.brand AS brand,
       s.status AS listing_status,
       COALESCE(s.available_units, 0) AS available_units,
       COALESCE(s.available_units, 0) AS wfs_available,
       0 AS non_wfs_available,
       NOW() AS created_at,
       CASE f.file_type WHEN 'WEEKLY_INVENTORY' THEN 'WEEKLY' ELSE 'LEGACY' END AS source_type
  FROM wm_ads_inventory_snapshot s
  JOIN wm_ads_import_file f ON f.id = s.import_file_id AND f.active = true;

-- 3) 视图改为单表投影（列名与 V49 完全一致，下游消费方零改动）
CREATE OR REPLACE VIEW wm_ads_inventory_unified AS
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

-- 4) 旧表改名归档（不 DROP，保留一键回滚；结构不变，实体 InventorySnapshot 的 @Table 已同步指向此名）
RENAME TABLE wm_ads_inventory_snapshot TO _deprecated_wm_ads_inventory_snapshot;
