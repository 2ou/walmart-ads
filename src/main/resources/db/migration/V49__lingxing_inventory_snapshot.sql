-- V49: 领星在线商品库存快照（以领星为主源，取代 Walmart 后台 inventory.xlsx 报表）
--
-- 设计要点
--  1. wm_ads_lx_inventory_snapshot 为领星库存快照表。按 business_date（业务日）做「整窗替换」写入，
--     即「每小时同步落库、每天只保留最后一版」：同一业务日内重复同步覆盖当天那批行，跨天则保留为历史。
--  2. 只保留领星真实能给、且下游真正在用的字段。原报表独有且填充率极低/无消费方的列
--     （reserved_units / sell_through_rate / item_lifecycle / daily_sales）不再入库、不再展示。
--  3. wm_ads_inventory_unified 是「旧报表快照（历史）∪ 领星快照（当期）」的统一只读视图，
--     供库存总览 / 补货计划 / 需求预测 / 销售分析 / 首页看板共用。
--     视图内部已按 import_file.active = true 过滤旧表，因此下游查询无需再联 wm_ads_import_file，
--     也不会因为旧表 inactive 批次而重复历史。
--  4. 旧表 wm_ads_inventory_snapshot 保留原名与全部历史数据（不归档、不删），仅停止写入，
--     以满足「趋势图不断档」的历史衔接要求。

CREATE TABLE wm_ads_lx_inventory_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    business_date DATE NOT NULL,
    snapshot_at DATETIME(6) NOT NULL,
    sku VARCHAR(180) NOT NULL,
    parent_sku VARCHAR(80) NULL,
    item_id VARCHAR(80) NULL,
    gtin VARCHAR(40) NULL,
    item_name TEXT NULL,
    brand VARCHAR(120) NULL,
    listing_status VARCHAR(80) NULL,
    available_units INT NOT NULL DEFAULT 0,
    wfs_available INT NULL,
    non_wfs_available INT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_lx_inventory_date (business_date),
    INDEX idx_lx_inventory_sku (sku, business_date),
    INDEX idx_lx_inventory_item (item_id, business_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 统一只读视图：列名与旧报表快照保持一致，领星侧缺失的列以 NULL 呈现（前端按「—」渲染）。
CREATE OR REPLACE VIEW wm_ads_inventory_unified AS
SELECT 'LEGACY' AS source_type,
       s.snapshot_at AS snapshot_at,
       s.sku AS sku,
       s.parent_sku AS parent_sku,
       s.item_id AS item_id,
       s.item_name AS item_name,
       s.status AS status,
       s.publishing_status AS publishing_status,
       s.brand AS brand,
       s.available_units AS available_units,
       NULL AS wfs_available,
       NULL AS non_wfs_available,
       NULL AS daily_units_sold,
       NULL AS reserved_units,
       NULL AS suggested_units,
       NULL AS surplus_units,
       NULL AS forecasted_days_supply,
       NULL AS out_of_stock_date,
       NULL AS sell_through_rate,
       NULL AS item_lifecycle,
       NULL AS inbound_units
  FROM wm_ads_inventory_snapshot s
  JOIN wm_ads_import_file f ON f.id = s.import_file_id AND f.active = true
UNION ALL
SELECT 'LINGXING' AS source_type,
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
