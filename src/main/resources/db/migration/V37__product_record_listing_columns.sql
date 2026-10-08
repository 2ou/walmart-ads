-- 产品库新增由「沃尔玛商品」扫描回写的列（价格 + 列表元数据）。
ALTER TABLE wm_ads_product_record
    ADD COLUMN price DECIMAL(18,6) NULL,
    ADD COLUMN upc VARCHAR(40) NULL,
    ADD COLUMN gtin VARCHAR(40) NULL,
    ADD COLUMN listing_status VARCHAR(40) NULL,
    ADD COLUMN store VARCHAR(40) NULL;
