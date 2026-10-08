-- V55: 领星 Walmart 店铺评价同步
--
-- 设计要点
--  1. wm_ads_lx_comment_raw 为评价明细表，业务键 (platform, comment_id)，评价内容可被买家编辑，
--     故同步走「按 comment_id upsert」而非按窗口整窗替换 —— 窗口内的旧评论会被最新内容覆盖。
--  2. parent_sku 在写入时物化：platform_item_id（Walmart Item ID）join wm_ads_sku_mapping.item_id。
--     实测 971 条评价 936 条命中（96%），未命中的历史下架款保留 parent_sku NULL，前端标「未归款」。
--     领星的 productMsku / tags / globalTagIds 恒为空数组，故不建列。
--  3. 评价接口无 31 天窗口上限（与广告接口不同），实测 2459 天窗口正常返回，
--     首次回填一次跑完即可；此后走每日滚动窗口增量。
--  4. wm_ads_lingxing_comment_sync_log 每次同步写一行，口径与 wm_ads_lingxing_order_sync_log 一致，
--     便于复用「最近一次同步 / 补窗」逻辑。

CREATE TABLE wm_ads_lx_comment_raw (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    platform VARCHAR(20) NOT NULL DEFAULT 'walmart',
    comment_id VARCHAR(40) NOT NULL,
    store_id VARCHAR(40) NOT NULL,
    store_name VARCHAR(80) NULL,
    platform_item_id VARCHAR(40) NOT NULL COMMENT 'Walmart Item ID，等同 wm_ads_sku_mapping.item_id',
    parent_sku VARCHAR(80) NULL COMMENT '归款结果，未命中留空',
    comment_date DATE NOT NULL,
    comment_star TINYINT NOT NULL,
    comment_title VARCHAR(500) NULL,
    comment_content MEDIUMTEXT NULL,
    buyer_name VARCHAR(100) NULL,
    product_title TEXT NULL,
    image_url VARCHAR(1000) NULL,
    buyer_flag TINYINT NULL COMMENT '领星原值，语义未在文档中明确，仅留档',
    site_code VARCHAR(20) NULL,
    raw_payload JSON NULL,
    synced_at DATETIME(6) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_lx_comment (platform, comment_id),
    INDEX idx_lx_comment_date (comment_date),
    INDEX idx_lx_comment_sku (parent_sku, comment_date),
    INDEX idx_lx_comment_item (platform_item_id),
    INDEX idx_lx_comment_star (comment_star, comment_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wm_ads_lingxing_comment_sync_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    status VARCHAR(20) NOT NULL COMMENT 'RUNNING / SUCCESS / FAILED',
    source VARCHAR(40) NOT NULL COMMENT '数据来源，恒为 lingxing_mcp',
    triggered_by VARCHAR(20) NOT NULL COMMENT 'CRON / MANUAL / BACKFILL',
    window_start DATE NOT NULL,
    window_end DATE NOT NULL,
    fetched_count INT NOT NULL DEFAULT 0 COMMENT '窗口内领星返回的评价条数',
    inserted_count INT NOT NULL DEFAULT 0 COMMENT '新写入条数',
    updated_count INT NOT NULL DEFAULT 0 COMMENT '内容更新（upsert 命中）条数',
    matched_sku_count INT NOT NULL DEFAULT 0 COMMENT '归到款号的条数',
    unmatched_count INT NOT NULL DEFAULT 0 COMMENT '未归款条数',
    avg_star DECIMAL(4, 2) NOT NULL DEFAULT 0 COMMENT '本次窗口均分（口径核对用）',
    duration_ms BIGINT NOT NULL DEFAULT 0,
    error_message TEXT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
