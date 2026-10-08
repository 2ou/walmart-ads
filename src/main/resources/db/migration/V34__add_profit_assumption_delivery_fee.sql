-- 在 wm_ads_profit_assumption 增加手工配送费列，支持整款级覆盖 ItemReport 反算值。
-- 注：MySQL 9.0 中 || 为逻辑 OR，本迁移不做任何字符串拼接，仅标准 ALTER。
ALTER TABLE wm_ads_profit_assumption
  ADD COLUMN delivery_fee DECIMAL(18, 6) NULL
  COMMENT '手工配送费（覆盖 ItemReport 反算值，整款级，单位与沃尔玛配送费一致）';
