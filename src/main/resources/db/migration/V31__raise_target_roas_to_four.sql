UPDATE wm_ads_campaign_decision_config
SET target_roas = 4.0
WHERE campaign_type IN ('自动广告', '手动广告');
