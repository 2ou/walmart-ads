CREATE TABLE wm_ads_campaign_decision_config (
    campaign_type VARCHAR(20) NOT NULL,
    min_clicks INT NOT NULL,
    min_spend DECIMAL(10, 2) NOT NULL,
    target_roas DECIMAL(6, 3) NOT NULL,
    floor_roas DECIMAL(6, 3) NOT NULL,
    PRIMARY KEY (campaign_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO wm_ads_campaign_decision_config (campaign_type, min_clicks, min_spend, target_roas, floor_roas) VALUES
    ('自动广告', 20, 10.0, 3.0, 1.5),
    ('手动广告', 20, 10.0, 3.0, 1.5);
