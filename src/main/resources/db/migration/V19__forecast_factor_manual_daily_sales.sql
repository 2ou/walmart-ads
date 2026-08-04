-- 需求预测工作台：支持按 SKU 手动覆盖日均销量（基础需求）
-- 非 null 时计算器用其替代系统派生的日均需求，再乘季节性/退货等因子，连锁重算建议件数
-- null 时回退到系统派生值（原速度法/混合法）
ALTER TABLE wm_ads_forecast_factor
  ADD COLUMN manual_daily_sales DECIMAL(18, 6) DEFAULT NULL
  COMMENT '手动覆盖的日均销量（件/天）；null=使用系统派生值'
  AFTER return_reuse_rate;
