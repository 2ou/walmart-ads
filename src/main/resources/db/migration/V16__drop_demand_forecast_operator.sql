-- 需求预测表移除「运营」字段（用户确认不再需要）。
-- V15 建表时含 operator 列，此处删除，使表结构与 DemandForecastRecord 实体一致。
ALTER TABLE wm_ads_demand_forecast DROP COLUMN operator;
