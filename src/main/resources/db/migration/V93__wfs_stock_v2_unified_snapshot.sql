-- V93: 一步到位接入领星 MCP wfs_stock_v2_list（查询WFS库存列表-v2）
-- 1. 产品库补充采购成本单价（RMB）；
-- 2. 库存日快照表补充 WFS 库龄（5 档）、预留/锁仓拆解、待入库、官方预测/冗余/售罄/断货日、采购成本；
-- 3. 重建 wm_ads_inventory_unified 统一视图，填平原 V49 置 NULL 的 8 列并透传新增 WFS 维度。

ALTER TABLE wm_ads_product_record
    ADD COLUMN purchase_cost DECIMAL(18,6) NULL COMMENT '领星WFS采购成本单价（RMB）';

ALTER TABLE wm_ads_lx_inventory_snapshot
    ADD COLUMN reserved_units INT NULL COMMENT 'WFS预留/冻结库存',
    ADD COLUMN inbound_units INT NULL COMMENT 'WFS待入库/在途合计',
    ADD COLUMN suggested_units INT NULL COMMENT '沃尔玛官方建议补货量',
    ADD COLUMN surplus_units INT NULL COMMENT '沃尔玛官方判定冗余/超储库存',
    ADD COLUMN forecasted_days_supply DECIMAL(18,4) NULL COMMENT '沃尔玛官方预计可售天数',
    ADD COLUMN out_of_stock_date DATE NULL COMMENT '沃尔玛官方断货发生日期',
    ADD COLUMN sell_through_rate DECIMAL(18,4) NULL COMMENT '沃尔玛官方售罄率',
    ADD COLUMN item_lifecycle VARCHAR(80) NULL COMMENT '沃尔玛商品生命周期',
    ADD COLUMN onhand_units INT NULL COMMENT 'WFS在库实物库存',
    ADD COLUMN unabled_warehousing_units INT NULL COMMENT 'WFS无法入库异常件数',
    ADD COLUMN inventory_review_units INT NULL COMMENT 'WFS盘点审核冻结件数',
    ADD COLUMN damaged_units INT NULL COMMENT 'WFS破损件数',
    ADD COLUMN ats_0_3m INT NULL COMMENT 'WFS库龄0-3个月件数',
    ADD COLUMN ats_3_6m INT NULL COMMENT 'WFS库龄3-6个月件数',
    ADD COLUMN ats_6_9m INT NULL COMMENT 'WFS库龄6-9个月件数',
    ADD COLUMN ats_9_12m INT NULL COMMENT 'WFS库龄9-12个月件数',
    ADD COLUMN ats_1y_plus INT NULL COMMENT 'WFS库龄1年以上件数',
    ADD COLUMN first_in_stock_date DATE NULL COMMENT 'WFS首次入仓日期',
    ADD COLUMN sales_forecast_w1_4 DECIMAL(18,4) NULL COMMENT '沃尔玛官方未来1-4周销量预测',
    ADD COLUMN sales_forecast_w5_8 DECIMAL(18,4) NULL COMMENT '沃尔玛官方未来5-8周销量预测',
    ADD COLUMN sales_forecast_w9_12 DECIMAL(18,4) NULL COMMENT '沃尔玛官方未来9-12周销量预测',
    ADD COLUMN wfs_stock_status VARCHAR(80) NULL COMMENT '沃尔玛官方WFS库存状态',
    ADD COLUMN customer_favorite TINYINT(1) NULL COMMENT '沃尔玛Customer Favorite标签',
    ADD COLUMN purchase_cost DECIMAL(18,6) NULL COMMENT '领星采购成本单价（RMB）';

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
       n.reserved_units AS reserved_units,
       n.suggested_units AS suggested_units,
       n.surplus_units AS surplus_units,
       n.forecasted_days_supply AS forecasted_days_supply,
       n.out_of_stock_date AS out_of_stock_date,
       n.sell_through_rate AS sell_through_rate,
       n.item_lifecycle AS item_lifecycle,
       n.inbound_units AS inbound_units,
       n.onhand_units AS onhand_units,
       n.unabled_warehousing_units AS unabled_warehousing_units,
       n.inventory_review_units AS inventory_review_units,
       n.damaged_units AS damaged_units,
       n.ats_0_3m AS ats_0_3m,
       n.ats_3_6m AS ats_3_6m,
       n.ats_6_9m AS ats_6_9m,
       n.ats_9_12m AS ats_9_12m,
       n.ats_1y_plus AS ats_1y_plus,
       n.first_in_stock_date AS first_in_stock_date,
       n.sales_forecast_w1_4 AS sales_forecast_w1_4,
       n.sales_forecast_w5_8 AS sales_forecast_w5_8,
       n.sales_forecast_w9_12 AS sales_forecast_w9_12,
       n.wfs_stock_status AS wfs_stock_status,
       n.customer_favorite AS customer_favorite,
       n.purchase_cost AS purchase_cost
  FROM wm_ads_lx_inventory_snapshot n;
