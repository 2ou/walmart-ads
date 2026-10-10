-- 货件级人工补充到达日期。
-- 用途：物流登记表查不到渠道、或系统推算的到达日已过期时，允许人工补录真实到达日，
-- 供路途货件明细分桶与倒计时使用。
-- 边界：只影响路途明细的分桶与展示，不参与需求预测的供应扣减（预测仍按渠道时效推算）。
CREATE TABLE wm_ads_transit_shipment_eta (
    workspace_id BIGINT NOT NULL DEFAULT 1,
    shipment_no VARCHAR(120) NOT NULL,
    arrival_date DATE NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (workspace_id, shipment_no),
    KEY idx_transit_shipment_eta_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='货件人工补充到达日期；仅用于路途明细分桶，不参与需求预测供应';
