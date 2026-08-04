-- 按款号（parentSku）维护的「日均销量(手动)」覆盖表。
-- 与 SKU 级因子表解耦：日均销量按款号设一次，该款下所有 SKU 套用同一值，
-- 重导 SKU / 需求预测表单都不会冲掉款号级设置。
CREATE TABLE wm_ads_forecast_factor_daily_parent (
    parent_sku       VARCHAR(180)  NOT NULL COMMENT '款号（因子键，唯一）',
    manual_daily_sales DECIMAL(18, 6) DEFAULT NULL COMMENT '手动覆盖的日均销量（覆盖系统派生值，null=回退派生）',
    updated_at       DATETIME      NOT NULL COMMENT '更新时间',
    PRIMARY KEY (parent_sku),
    INDEX idx_forecast_factor_parent_updated (updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '按款号的日均销量手动覆盖（系统维护）';

-- V19 误把 manual_daily_sales 放进 SKU 级因子表，
-- 现上移到款号维度，删除该冗余列以保持模型自洽（确认无业务数据）。
ALTER TABLE wm_ads_forecast_factor DROP COLUMN manual_daily_sales;
