CREATE TABLE wm_ads_replenishment_decision (
    parent_sku VARCHAR(180) NOT NULL,
    final_quantity DECIMAL(18, 4) NULL,
    system_suggested_quantity DECIMAL(18, 4) NULL,
    demand_plan_quantity DECIMAL(18, 4) NULL,
    target_days INT NULL,
    decision_status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    decision_note VARCHAR(500) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (parent_sku),
    INDEX idx_replenishment_decision_status (decision_status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='按款号维护的人工最终补货决策';
