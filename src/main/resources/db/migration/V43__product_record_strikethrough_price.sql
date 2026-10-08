-- 产品库增加沃尔玛在线列表导出的划线价（按 SKU 维护）。
ALTER TABLE wm_ads_product_record
    ADD COLUMN strikethrough_price DECIMAL(18, 6) NULL
    COMMENT '沃尔玛在线列表划线价（USD），来自 source/沃尔玛商品/*.xlsx 的划线价列';
