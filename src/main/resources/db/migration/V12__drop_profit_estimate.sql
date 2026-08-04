-- 毛利估算改为纯前端计算器，不再落库，删除原明细表。
-- 保留 V10（建表）/ V11（种子）以兼容 Flyway 历史；本迁移负责回收该表。
DROP TABLE IF EXISTS wm_ads_profit_estimate;
