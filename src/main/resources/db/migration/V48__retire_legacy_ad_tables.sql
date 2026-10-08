-- 领星 MCP 已完全接管广告数据采集（V46：wm_ads_lx_ad_report / wm_ads_lx_ad_slice，每小时同步）。
-- 原人工 CSV 落库的两张表自 2026-09-20 / 2026-09-22 起停止更新，代码侧已无任何实体或查询引用，
-- 故在此「改名归档」而不是 DROP —— 数据完整保留、随时可回滚：
--   RENAME TABLE _deprecated_wm_ads_ad_performance TO wm_ads_ad_performance;
--   RENAME TABLE _deprecated_wm_ads_ad_campaign_snapshot TO wm_ads_ad_campaign_snapshot;
-- 若确认永不回退，可改为物理删除：
--   DROP TABLE _deprecated_wm_ads_ad_performance;
--   DROP TABLE _deprecated_wm_ads_ad_campaign_snapshot;
RENAME TABLE wm_ads_ad_performance TO _deprecated_wm_ads_ad_performance;
RENAME TABLE wm_ads_ad_campaign_snapshot TO _deprecated_wm_ads_ad_campaign_snapshot;
