-- 补货预测输入因子表：系统维护，独立于需求预测 Excel 导入（非版本化，按 SKU 唯一）。
-- 重导需求预测表单不会清除这里用户内联编辑的因子。因子缺省(null)时计算器自动退化为原速度法。
CREATE TABLE wm_ads_forecast_factor (
    sku VARCHAR(180) NOT NULL COMMENT '商品 SKU（因子键，唯一）',
    seasonal_coef DECIMAL(18, 6) DEFAULT NULL COMMENT '季节性系数（乘数，默认 1）',
    gain_days DECIMAL(10, 2) DEFAULT NULL COMMENT '增益天数（叠加进目标覆盖天数）',
    safety_days DECIMAL(10, 2) DEFAULT NULL COMMENT '安全库存天数（叠加进目标覆盖天数）',
    return_rate DECIMAL(18, 6) DEFAULT NULL COMMENT '退货率（0~1）',
    return_reuse_rate DECIMAL(18, 6) DEFAULT NULL COMMENT '退货重复利用率（0~1）',
    updated_at DATETIME NOT NULL COMMENT '更新时间',
    PRIMARY KEY (sku),
    INDEX idx_forecast_factor_updated (updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '补货预测输入因子（系统维护）';
