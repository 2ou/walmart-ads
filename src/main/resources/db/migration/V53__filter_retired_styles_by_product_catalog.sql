-- V53: 全局「有效款」口径 —— 只展示产品库当前版本内的款，淘汰款 / 未导入款一律不参与展示。
--
-- 背景：广告 / 订单 / 库存三路数据由领星同步写入，只要 Walmart 广告账号历史上投过就会留行。
--       其中 8 个款（PNK361002 / PNK366067 ~ PNK366073）从未进过产品库（上传表），
--       属于 campaign 已 paused、无订单（停在 2025-10 ~ 2026-03）、最新快照 0 件的淘汰款，
--       但仍占广告表近 30 天 16.5% 的行数（paused campaign 每天回零值行），在广告页与库存趋势里形成噪音。
--       花定：以产品库为准，不在产品的款不展示，淘汰款不需要保留展示。
--
-- 口径：parent_sku 必须存在于 wm_ads_product_record（current_version = 1）中。
--       ★ 例外：广告表 KEYWORD / AD_GROUP / CAMPAIGN 级行的 parent_sku 恒为空（无法归属到款），
--         必须放行，否则整个关键词与 campaign 分析会整体消失；
--         只有 AD_ITEM 级（139,979 行，parent_sku 全非空）按款过滤。
--
-- 实现：不改 60+ 处业务 SQL，改数据对象本身 ——
--       ① 库存：视图 wm_ads_inventory_unified 加过滤 → 下游 6 个消费方零改动
--       ② 广告：原表 RENAME 为 _raw 专供同步写入，同名 MERGE 视图供业务查询
--               → 读查询零改动，仅 WalmartAdStore 的 3 处写入 / 覆盖检查改指向 _raw
--       ③ 订单：原表有 JPA 实体 OrderRecord（ddl-auto=validate 下不可改名），
--               故另建 wm_ads_order_record_valid 视图供读，读查询改指向该视图
--
-- 回滚（确认不回头再执行）：
--   DROP VIEW wm_ads_lx_ad_report;
--   RENAME TABLE wm_ads_lx_ad_report_raw TO wm_ads_lx_ad_report;
--   DROP VIEW wm_ads_order_record_valid;
--   库存视图还原为 V50 的无过滤版本（去掉 WHERE 行）。
-- 注意：广告视图用 SELECT *，列在创建时展开；日后 wm_ads_lx_ad_report_raw 加列需重建本视图。

-- 1) 索引：支撑按 parent_sku 过滤（产品库已有 idx_product_parent，无需另建）
ALTER TABLE wm_ads_lx_ad_report
    ADD INDEX idx_lx_ad_report_parent (parent_sku);
ALTER TABLE wm_ads_order_record
    ADD INDEX idx_order_parent (parent_sku);

-- 2) 库存统一视图：只保留产品库内的款（列名与 V50 完全一致 → 下游零改动）
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
  FROM wm_ads_lx_inventory_snapshot s
 WHERE s.parent_sku IN (SELECT p.parent_sku
                          FROM wm_ads_product_record p
                         WHERE p.current_version = 1);

-- 3) 广告表：原表改名供同步写入，同名视图供业务查询
RENAME TABLE wm_ads_lx_ad_report TO wm_ads_lx_ad_report_raw;

CREATE OR REPLACE ALGORITHM=MERGE VIEW wm_ads_lx_ad_report AS
SELECT *
  FROM wm_ads_lx_ad_report_raw r
 WHERE r.parent_sku IS NULL
    OR r.parent_sku = ''
    OR r.parent_sku IN (SELECT p.parent_sku
                          FROM wm_ads_product_record p
                         WHERE p.current_version = 1);

-- 4) 订单有效视图（原表保留给 JPA 实体 OrderRecord，另建视图供读）
CREATE OR REPLACE ALGORITHM=MERGE VIEW wm_ads_order_record_valid AS
SELECT *
  FROM wm_ads_order_record o
 WHERE o.parent_sku IN (SELECT p.parent_sku
                          FROM wm_ads_product_record p
                         WHERE p.current_version = 1);
