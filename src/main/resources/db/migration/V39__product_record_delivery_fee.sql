-- 产品库增加「单件实际配送费」列（按 SKU 维护）。
-- 数据来源：wm_ads_wfs_fulfillment_fee（WFS 结算报表），仅取 transaction_type='FulfillmentFee' 且 qty=1 的单件标准行，
-- 取该 SKU 最近一期 net_payable 的中位数作为维护值。回填由 scripts/backfill_product_delivery_fee.py 完成。
-- 该列仅作真实实测配送费兜底：利润计算优先级为 整款级手工配送费(manualDeliveryFee) > 本列实测值。
ALTER TABLE wm_ads_product_record
    ADD COLUMN delivery_fee DECIMAL(18, 6) NULL
    COMMENT '单件实际配送费（USD，来自 WFS 配送费报表 qty=1 标准，按 SKU 维护；利润计算兜底）';
