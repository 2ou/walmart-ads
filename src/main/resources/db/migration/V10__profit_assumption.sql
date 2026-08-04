CREATE TABLE wm_ads_profit_assumption (
    parent_sku VARCHAR(80) PRIMARY KEY,
    purchase_cost DECIMAL(18,6) NULL,
    first_leg DECIMAL(18,6) NULL,
    sales_qty DECIMAL(18,6) NULL,
    ad_cost DECIMAL(18,6) NOT NULL DEFAULT 1,
    refund_rate DECIMAL(10,6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_profit_assumption_parent (parent_sku)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
