CREATE TABLE wm_ads_ad_campaign_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    import_file_id BIGINT NOT NULL,
    source_updated_at DATETIME(6) NOT NULL,
    current_version BOOLEAN NOT NULL DEFAULT TRUE,
    campaign_id VARCHAR(80) NOT NULL,
    campaign_name VARCHAR(500) NOT NULL,
    campaign_type VARCHAR(80) NULL,
    targeting_type VARCHAR(80) NULL,
    campaign_status VARCHAR(40) NULL,
    budget_type VARCHAR(40) NULL,
    daily_budget DECIMAL(18,6) NOT NULL DEFAULT 0,
    start_date DATE NULL,
    end_date DATE NULL,
    bidding_strategy VARCHAR(80) NULL,
    channel VARCHAR(120) NULL,
    raw_payload JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_campaign_snapshot_import_file
        FOREIGN KEY (import_file_id) REFERENCES wm_ads_import_file(id),
    INDEX idx_campaign_snapshot_id_current (campaign_id, current_version),
    INDEX idx_campaign_snapshot_status_current (campaign_status, current_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wm_ads_ad_campaign_item_snapshot (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    import_file_id BIGINT NOT NULL,
    source_updated_at DATETIME(6) NOT NULL,
    current_version BOOLEAN NOT NULL DEFAULT TRUE,
    campaign_id VARCHAR(80) NOT NULL,
    ad_group_id VARCHAR(80) NULL,
    item_id VARCHAR(80) NOT NULL,
    ad_item_id VARCHAR(80) NULL,
    bid DECIMAL(18,6) NOT NULL DEFAULT 0,
    item_status VARCHAR(40) NULL,
    item_name TEXT NULL,
    item_image_url VARCHAR(2500) NULL,
    item_page_url VARCHAR(2500) NULL,
    review_status VARCHAR(40) NULL,
    review_reason TEXT NULL,
    raw_payload JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_campaign_item_snapshot_import_file
        FOREIGN KEY (import_file_id) REFERENCES wm_ads_import_file(id),
    INDEX idx_campaign_item_snapshot_campaign_current (campaign_id, current_version),
    INDEX idx_campaign_item_snapshot_item_current (item_id, current_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
