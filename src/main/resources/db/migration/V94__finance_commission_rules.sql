CREATE TABLE wm_ads_finance_commission_rule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    item_id VARCHAR(24) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    maximum_price_usd DECIMAL(12, 2) NOT NULL,
    rate DECIMAL(8, 6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_fin_commission_item_dates (workspace_id, item_id, start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
