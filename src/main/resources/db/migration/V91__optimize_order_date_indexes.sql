-- Optimize order_date range scans and style-level order/operation lookups across
-- Dashboard, Sales, Order Flow, Recent Data, Product Dashboard, and Campaign Diagnosis.
CREATE INDEX idx_order_workspace_order_date_parent_sku
    ON wm_ads_order_record (workspace_id, order_date, parent_sku, sku);

CREATE INDEX idx_order_workspace_parent_order_date
    ON wm_ads_order_record (workspace_id, parent_sku, order_date);

CREATE INDEX idx_operation_workspace_parent_status
    ON wm_ads_operation_record (workspace_id, parent_sku, status);
