-- 产品库新增「裁床数」字段：一条颜色的布能裁多少件，用于做货数量下单参考
ALTER TABLE wm_ads_product_record
    ADD COLUMN cutting_yield INT NULL COMMENT '裁床数：一条颜色的布能裁多少件，用于做货数量下单参考（可空）';
