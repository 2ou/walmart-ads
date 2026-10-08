ALTER TABLE wm_ads_profit_assumption
    ADD COLUMN prefer_manual_refund_rate BOOLEAN NOT NULL DEFAULT FALSE
    COMMENT '是否优先采用手工退款率；默认使用款号累计总退款率'
    AFTER refund_rate;
