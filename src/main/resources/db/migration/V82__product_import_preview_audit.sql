CREATE TABLE wm_ads_product_import_change (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    import_file_id BIGINT NOT NULL,
    action VARCHAR(20) NOT NULL,
    sku VARCHAR(180) NOT NULL,
    parent_sku VARCHAR(80) NULL,
    product_name TEXT NULL,
    color VARCHAR(600) NULL,
    size VARCHAR(120) NULL,
    restored BOOLEAN NOT NULL DEFAULT FALSE,
    whole_style BOOLEAN NOT NULL DEFAULT FALSE,
    field_changes JSON NOT NULL,
    operated_by VARCHAR(180) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_product_import_change_file
        FOREIGN KEY (import_file_id) REFERENCES wm_ads_import_file(id),
    INDEX idx_product_import_change_workspace_file (workspace_id, import_file_id),
    INDEX idx_product_import_change_workspace_action (workspace_id, action),
    INDEX idx_product_import_change_workspace_sku (workspace_id, sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
