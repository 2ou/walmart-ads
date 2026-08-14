-- 产品库新增「开售时间」字段：该父款最早有销量日；无销量为空（新品）
ALTER TABLE wm_ads_product_record
    ADD COLUMN launch_date DATE NULL COMMENT '开售时间：该父款最早有销量日；无销量为空（新品）';
