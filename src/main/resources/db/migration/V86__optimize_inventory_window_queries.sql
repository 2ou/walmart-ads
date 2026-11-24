-- Inventory overview reads the newest snapshot, current product catalog and recent SKU sales together.
-- These tenant-first indexes keep those reads bounded as snapshot history grows.
CREATE INDEX idx_lx_inventory_workspace_snapshot
    ON wm_ads_lx_inventory_snapshot (workspace_id, snapshot_at);

CREATE INDEX idx_lx_inventory_workspace_parent_snapshot
    ON wm_ads_lx_inventory_snapshot (workspace_id, parent_sku, snapshot_at);

CREATE INDEX idx_order_workspace_report_sku
    ON wm_ads_order_record (workspace_id, report_date, sku);

CREATE INDEX idx_product_workspace_current_parent_sku
    ON wm_ads_product_record (workspace_id, current_version, parent_sku, sku);
