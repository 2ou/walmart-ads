CREATE TABLE wm_ads_sku_mapping (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    import_file_id BIGINT NOT NULL,
    source_updated_at DATETIME(6) NOT NULL,
    current_version BOOLEAN NOT NULL DEFAULT TRUE,
    sku VARCHAR(180) NOT NULL,
    parent_sku VARCHAR(80) NOT NULL,
    item_id VARCHAR(80) NOT NULL,
    gtin VARCHAR(40) NOT NULL,
    upc VARCHAR(40) NOT NULL,
    item_name TEXT NULL,
    raw_payload JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_sku_mapping_import_file
        FOREIGN KEY (import_file_id) REFERENCES wm_ads_import_file(id),
    INDEX idx_sku_mapping_sku_current (sku, current_version),
    INDEX idx_sku_mapping_item_current (item_id, current_version),
    INDEX idx_sku_mapping_upc_current (upc, current_version),
    INDEX idx_sku_mapping_parent (parent_sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
