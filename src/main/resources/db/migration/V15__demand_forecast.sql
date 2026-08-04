-- 需求预测计划值表：仅存储 Excel 独有的计划字段，按 SKU 版本化。
-- 实际值（颜色/品名/WFS可售/近30天销量/近3月日均/周转天数/退货率）由系统现有数据源关联，不在此表。
CREATE TABLE wm_ads_demand_forecast (
    id BIGINT NOT NULL AUTO_INCREMENT,
    import_file_id BIGINT NOT NULL,
    current_version TINYINT(1) NOT NULL DEFAULT 1 COMMENT '是否当前版本（1是/0否）',
    sku VARCHAR(180) NOT NULL COMMENT '商品 SKU（计划值键）',
    operator VARCHAR(120) DEFAULT NULL COMMENT '运营（Excel 计划字段）',
    forecast_pmc DECIMAL(18, 4) DEFAULT NULL COMMENT '预计销量(PMC)（件）',
    forecast_ops DECIMAL(18, 4) DEFAULT NULL COMMENT '预计销量(运营)（件）',
    pct_gain DECIMAL(18, 6) DEFAULT NULL COMMENT '百分比增益（小数，如 0.2=20%）',
    reuse_rate DECIMAL(18, 6) DEFAULT NULL COMMENT '退货重复利用率（小数）',
    demand_plan DECIMAL(18, 4) DEFAULT NULL COMMENT '需求计划（件）',
    actual_order DECIMAL(18, 4) DEFAULT NULL COMMENT '实际下单量（件）',
    ship_qty DECIMAL(18, 4) DEFAULT NULL COMMENT '发货量（件）',
    remaining DECIMAL(18, 4) DEFAULT NULL COMMENT '剩余（件）',
    raw_payload JSON NOT NULL COMMENT '原始报文（JSON）',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    CONSTRAINT fk_demand_forecast_file FOREIGN KEY (import_file_id) REFERENCES wm_ads_import_file(id),
    INDEX idx_demand_forecast_sku (sku),
    INDEX idx_demand_forecast_current (current_version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '需求预测计划值（Excel 导入）';
