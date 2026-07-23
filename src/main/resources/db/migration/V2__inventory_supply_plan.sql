CREATE TABLE wm_ads_inventory_supply_plan (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    sku VARCHAR(180) NOT NULL,
    production_units DECIMAL(18,4) NOT NULL DEFAULT 0,
    note VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_inventory_supply_plan_sku_created (sku, created_at),
    INDEX idx_inventory_supply_plan_sku_id (sku, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
