-- 沃尔玛在线商品改为领星 MCP 每小时同步回写产品库（替代 source/沃尔玛商品 文件扫描导入）。
-- 新增实时运营列；color/size/pattern/material/key_features 等营销属性列一律不动，仍由产品库维护。
ALTER TABLE wm_ads_product_record
    ADD COLUMN wfs_available INT NULL COMMENT 'WFS 可售库存，来自领星 MCP 沃尔玛在线商品（每小时同步）',
    ADD COLUMN non_wfs_available INT NULL COMMENT '非 WFS（自发货）可售库存，来自领星 MCP',
    ADD COLUMN buy_box_price DECIMAL(18, 6) NULL COMMENT 'Buy Box 价格（USD），来自领星 MCP',
    ADD COLUMN review_count INT NULL COMMENT '评论数，来自领星 MCP',
    ADD COLUMN average_rating DECIMAL(6, 2) NULL COMMENT '平均评分，来自领星 MCP',
    ADD COLUMN competitor_price DECIMAL(18, 6) NULL COMMENT '竞品价格（USD），来自领星 MCP',
    ADD COLUMN fulfillment_type INT NULL COMMENT '履约类型代码（1=WFS），来自领星 MCP',
    ADD COLUMN fulfillment_type_name VARCHAR(60) NULL COMMENT '履约类型名称，来自领星 MCP',
    ADD COLUMN listing_start_time DATETIME NULL COMMENT '上架时间，来自领星 MCP',
    ADD COLUMN offer_end_date DATETIME NULL COMMENT '报价结束时间，来自领星 MCP',
    ADD COLUMN item_url VARCHAR(600) NULL COMMENT '沃尔玛商品详情页 URL，来自领星 MCP',
    ADD COLUMN promotion_json JSON NULL COMMENT '促销明细（promotion_data_list），来自领星 MCP',
    ADD COLUMN last_synced_at DATETIME NULL COMMENT '最近一次领星 MCP 同步时间';

-- 沃尔玛在线商品 MCP 同步落库记录（每次 CRON/MANUAL 同步写一行，便于排查与审计）。
CREATE TABLE wm_ads_walmart_listing_sync_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    started_at DATETIME NOT NULL,
    finished_at DATETIME NULL,
    status VARCHAR(20) NOT NULL COMMENT 'SUCCESS/FAILED/PARTIAL',
    source VARCHAR(40) NOT NULL COMMENT '数据来源，恒为 lingxing_mcp',
    fetched_count INT NOT NULL,
    updated_count INT NOT NULL,
    inserted_count INT NOT NULL COMMENT '恒为 0（只回写已存在行，不新建 SKU）',
    error_message TEXT NULL,
    triggered_by VARCHAR(20) NOT NULL COMMENT 'CRON/MANUAL',
    duration_ms BIGINT NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_sync_log_started (started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '沃尔玛在线商品 MCP 同步落库记录';
