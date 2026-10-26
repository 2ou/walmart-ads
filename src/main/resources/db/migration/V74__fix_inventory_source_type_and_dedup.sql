-- V74: 修复库存统一视图 source_type 透传 + 清理 2026-07-27 双源重复行 + 加唯一约束
--
-- 背景（完整诊断见 D:/WorkBuddyData/walmart-ads/2026-07-27-可售库存翻倍诊断.md）：
--   ① 数据层：V50 把旧报表库存并入 wm_ads_lx_inventory_snapshot 时，搬迁语句
--      (INSERT ... SELECT) 未按 (business_date, sku) 去重。旧表里 2026-07-27（周一）恰好
--      同时存在旧「日报」(LEGACY) 与旧「周报」(WEEKLY) 两批快照，两批行被原样搬入
--      → 同日同 SKU 各两条（LEGACY 664 + WEEKLY 662 = 1326 行 / 664 个 distinct sku）。
--   ② 查询层：V65 重写 wm_ads_inventory_unified 视图时，把 source_type 由「透传底层表」
--      改成硬编码常量 'LINGXING'，使 InventoryDataService.loadTrend() 中按 snapshot_at
--      选一个来源的去重 CTE 失效（join 条件恒为真），两批行被直接相加
--      → 趋势图 2026-07-27 可售库存由正确值 9,227 虚高成 18,520（约 2 倍）。
--
-- 本迁移把两层一起修掉，所有语句幂等，可安全重复执行：
--   ① 备份 2026-07-27 全部行到 _bak_lx_inventory_snapshot_20260727（保留一键回滚）
--   ② 按优先级 LINGXING > LEGACY > WEEKLY 删除同日同 SKU 的重复行（并顺手清理同源重复）
--   ③ 重建视图，恢复 source_type 透传（这是让尖峰消失的关键）
--   ④ 加 UNIQUE (business_date, sku)，从表结构上杜绝「同日同 SKU 重复」再次发生
--
-- 回滚：
--   INSERT INTO wm_ads_lx_inventory_snapshot SELECT * FROM _bak_lx_inventory_snapshot_20260727;
--   并把视图 source_type 改回常量 'LINGXING'（不建议）。

-- ① 备份当天全部行（幂等）
CREATE TABLE IF NOT EXISTS _bak_lx_inventory_snapshot_20260727 AS
SELECT * FROM wm_ads_lx_inventory_snapshot WHERE business_date = '2026-07-27';

-- ②-a 同源完全重复：同一 (business_date, sku, source_type) 只保留 id 最小的一条
DELETE s
  FROM wm_ads_lx_inventory_snapshot s
  JOIN wm_ads_lx_inventory_snapshot k
    ON k.business_date = s.business_date
   AND k.sku           = s.sku
   AND k.source_type   = s.source_type
   AND k.id            < s.id;

-- ②-b 跨源重复：同一 (business_date, sku) 保留优先级最高的来源
--     FIELD() 返回 1-based 下标，下标越小优先级越高 → 删除下标更大的（低优先级）行
DELETE s
  FROM wm_ads_lx_inventory_snapshot s
  JOIN wm_ads_lx_inventory_snapshot k
    ON k.business_date = s.business_date
   AND k.sku           = s.sku
   AND FIELD(k.source_type, 'LINGXING', 'LEGACY', 'WEEKLY')
     < FIELD(s.source_type, 'LINGXING', 'LEGACY', 'WEEKLY');

-- ③ 重建视图：source_type 由常量 'LINGXING' 改回透传 n.source_type
--    （列名与顺序保持与 V65 完全一致，下游 8 处消费方 SQL 零改动）
CREATE OR REPLACE VIEW wm_ads_inventory_unified AS
SELECT n.source_type AS source_type,
       n.workspace_id AS workspace_id,
       n.snapshot_at AS snapshot_at,
       n.sku AS sku,
       n.parent_sku AS parent_sku,
       n.item_id AS item_id,
       n.item_name AS item_name,
       n.listing_status AS status,
       n.listing_status AS publishing_status,
       n.brand AS brand,
       n.available_units AS available_units,
       n.wfs_available AS wfs_available,
       n.non_wfs_available AS non_wfs_available,
       NULL AS daily_units_sold,
       NULL AS reserved_units,
       NULL AS suggested_units,
       NULL AS surplus_units,
       NULL AS forecasted_days_supply,
       NULL AS out_of_stock_date,
       NULL AS sell_through_rate,
       NULL AS item_lifecycle,
       NULL AS inbound_units
  FROM wm_ads_lx_inventory_snapshot n;

-- ④ 唯一约束（幂等：索引已存在则跳过）
--    这一条才是真正的护栏 —— 数据清理只是把历史遗留修掉，唯一键保证不再复发。
--    LxInventorySnapshotWriter.replaceDay() 是「按 businessDate 先删后插」，与本键不冲突。
SET @idx_exists := (
    SELECT COUNT(*)
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME   = 'wm_ads_lx_inventory_snapshot'
       AND INDEX_NAME   = 'uk_lx_inventory_day_sku'
);
SET @ddl := IF(@idx_exists = 0,
    'ALTER TABLE wm_ads_lx_inventory_snapshot ADD UNIQUE KEY uk_lx_inventory_day_sku (business_date, sku)',
    'DO 0');
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
