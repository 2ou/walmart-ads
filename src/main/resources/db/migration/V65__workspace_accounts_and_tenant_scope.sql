-- Workspace/account foundation. Existing rows belong to the original PINKSIR workspace (id=1).
-- The workspace itself is created by the localhost-only first-run setup so no password is seeded.
CREATE TABLE wm_ads_workspace (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING_SETUP',
    session_version BIGINT NOT NULL DEFAULT 1,
    store_id VARCHAR(80) NULL,
    store_name VARCHAR(160) NULL,
    store_alias VARCHAR(160) NULL,
    sku_prefixes VARCHAR(1000) NULL,
    mcp_base_url VARCHAR(500) NULL,
    mcp_key TEXT NULL,
    mcp_key_last4 VARCHAR(4) NULL,
    mcp_verified_at DATETIME(6) NULL,
    initialization_status VARCHAR(32) NOT NULL DEFAULT 'PENDING_CONFIG',
    initialization_message VARCHAR(1000) NULL,
    initialization_started_at DATETIME(6) NULL,
    initialization_completed_at DATETIME(6) NULL,
    supply_chain_enabled TINYINT(1) NOT NULL DEFAULT 0,
    primary_workspace TINYINT(1) NOT NULL DEFAULT 0,
    created_by_workspace_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_workspace_username (username),
    UNIQUE KEY uk_workspace_store_id (store_id),
    KEY idx_workspace_status (status),
    CONSTRAINT fk_workspace_creator FOREIGN KEY (created_by_workspace_id) REFERENCES wm_ads_workspace(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE wm_ads_workspace_advertiser (
    workspace_id BIGINT NOT NULL,
    advertiser_id BIGINT NOT NULL,
    advertiser_name VARCHAR(160) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id, advertiser_id),
    UNIQUE KEY uk_workspace_advertiser_owner (advertiser_id),
    CONSTRAINT fk_workspace_advertiser_workspace FOREIGN KEY (workspace_id) REFERENCES wm_ads_workspace(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wm_ads_workspace_session (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    token_hash CHAR(64) NOT NULL,
    session_version BIGINT NOT NULL,
    ip_address VARCHAR(64) NULL,
    user_agent VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_seen_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_workspace_session_token (token_hash),
    KEY idx_workspace_session_owner (workspace_id, expires_at),
    CONSTRAINT fk_workspace_session_workspace FOREIGN KEY (workspace_id) REFERENCES wm_ads_workspace(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wm_ads_workspace_recovery_code (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    used_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    KEY idx_workspace_recovery_owner (workspace_id, used_at),
    CONSTRAINT fk_workspace_recovery_workspace FOREIGN KEY (workspace_id) REFERENCES wm_ads_workspace(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wm_ads_workspace_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NULL,
    username VARCHAR(64) NULL,
    event_type VARCHAR(64) NOT NULL,
    success TINYINT(1) NOT NULL,
    summary VARCHAR(1000) NULL,
    ip_address VARCHAR(64) NULL,
    user_agent VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_workspace_audit_owner_time (workspace_id, created_at),
    CONSTRAINT fk_workspace_audit_workspace FOREIGN KEY (workspace_id) REFERENCES wm_ads_workspace(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wm_ads_workspace_sync_setting (
    workspace_id BIGINT NOT NULL,
    sync_category VARCHAR(32) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    cadence VARCHAR(32) NOT NULL,
    stagger_minute INT NOT NULL DEFAULT 0,
    last_started_at DATETIME(6) NULL,
    last_completed_at DATETIME(6) NULL,
    last_status VARCHAR(24) NULL,
    last_message VARCHAR(1000) NULL,
    PRIMARY KEY (workspace_id, sync_category),
    CONSTRAINT fk_workspace_sync_workspace FOREIGN KEY (workspace_id) REFERENCES wm_ads_workspace(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Tenant discriminator. DEFAULT 1 migrates the complete existing dataset to PINKSIR without rewriting facts.
ALTER TABLE wm_ads_import_batch ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_import_batch_workspace (workspace_id);
ALTER TABLE wm_ads_import_file ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_import_file_workspace (workspace_id);
ALTER TABLE wm_ads_order_record ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_order_workspace_date (workspace_id, order_date);
ALTER TABLE wm_ads_product_record ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_product_workspace_sku (workspace_id, sku);
ALTER TABLE wm_ads_rule_result ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_rule_workspace (workspace_id);
ALTER TABLE wm_ads_ai_analysis ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_ai_analysis_workspace (workspace_id);
ALTER TABLE wm_ads_inventory_supply_plan ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_supply_plan_workspace (workspace_id);
ALTER TABLE wm_ads_sku_mapping ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_sku_mapping_workspace (workspace_id);
ALTER TABLE wm_ads_operation_record ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_operation_workspace (workspace_id);
ALTER TABLE wm_ads_return_order ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_return_workspace (workspace_id);
ALTER TABLE wm_ads_profit_assumption ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1 FIRST;
ALTER TABLE wm_ads_demand_forecast ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_demand_workspace (workspace_id);
ALTER TABLE wm_ads_production_order ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_production_workspace (workspace_id);
ALTER TABLE wm_ads_forecast_factor ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1 FIRST;
ALTER TABLE wm_ads_forecast_factor_daily_parent ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1 FIRST;
ALTER TABLE wm_ads_shipment_record ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_shipment_workspace (workspace_id);
ALTER TABLE wm_ads_shipment_logistics ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_shipment_logistics_workspace (workspace_id);
ALTER TABLE wm_ads_transit_channel_lead_time ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1 FIRST;
ALTER TABLE wm_ads_replenishment_decision ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1 FIRST;
ALTER TABLE wm_ads_domestic_inventory_snapshot ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_domestic_inventory_workspace (workspace_id);
ALTER TABLE wm_ads_parent_lifecycle ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1 FIRST;
ALTER TABLE wm_ads_campaign_decision_config ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1 FIRST;
ALTER TABLE wm_ads_operation_reminder ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_operation_reminder_workspace (workspace_id);
ALTER TABLE wm_ads_review_task ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_review_task_workspace (workspace_id);
ALTER TABLE wm_ads_wfs_storage_fee ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_wfs_storage_workspace (workspace_id);
ALTER TABLE wm_ads_wfs_fulfillment_fee ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_wfs_fulfillment_workspace (workspace_id);
ALTER TABLE wm_ads_walmart_listing_sync_log ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_listing_sync_workspace (workspace_id);
ALTER TABLE wm_ads_lingxing_order_sync_log ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_order_sync_workspace (workspace_id);
ALTER TABLE wm_ads_lx_ad_report_raw ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_ad_report_workspace_date (workspace_id, report_date);
ALTER TABLE wm_ads_lx_ad_slice ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_ad_slice_workspace_date (workspace_id, report_date);
ALTER TABLE wm_ads_lx_ad_sync_log ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_ad_sync_workspace (workspace_id);
ALTER TABLE wm_ads_lx_item_sku_alias ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1 FIRST;
ALTER TABLE wm_ads_lx_inventory_snapshot ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_lx_inventory_workspace_date (workspace_id, business_date);
ALTER TABLE wm_ads_lx_comment_raw ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_comment_workspace_date (workspace_id, comment_date);
ALTER TABLE wm_ads_lingxing_comment_sync_log ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_comment_sync_workspace (workspace_id);
ALTER TABLE wm_ads_forecast_setting ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1 FIRST;
ALTER TABLE wm_ads_ai_agent_run ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_agent_run_workspace (workspace_id);
ALTER TABLE wm_ads_ai_conversation ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_ai_conversation_workspace (workspace_id);
ALTER TABLE wm_ads_ai_conversation_message ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_ai_message_workspace (workspace_id);
ALTER TABLE wm_ads_price_baseline ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1 FIRST;
ALTER TABLE wm_ads_price_change ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_price_change_workspace (workspace_id);
ALTER TABLE wm_ads_supply_production_order ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_supply_production_workspace (workspace_id);
ALTER TABLE wm_ads_supply_delivery_detail ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_supply_delivery_workspace (workspace_id);
ALTER TABLE wm_ads_supply_chain_sync_log ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_supply_sync_workspace (workspace_id);

-- Retired tables remain scoped as rollback/history sources.
ALTER TABLE _deprecated_wm_ads_ad_performance ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_deprecated_ad_workspace (workspace_id);
ALTER TABLE _deprecated_wm_ads_ad_campaign_snapshot ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_deprecated_campaign_workspace (workspace_id);
ALTER TABLE _deprecated_wm_ads_ad_campaign_item_snapshot ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_deprecated_campaign_item_workspace (workspace_id);
ALTER TABLE _deprecated_wm_ads_inventory_snapshot ADD COLUMN workspace_id BIGINT NOT NULL DEFAULT 1, ADD KEY idx_deprecated_inventory_workspace (workspace_id);

-- Natural keys and source uniqueness are tenant-local, not global.
ALTER TABLE wm_ads_profit_assumption DROP PRIMARY KEY, ADD PRIMARY KEY (workspace_id, parent_sku);
ALTER TABLE wm_ads_forecast_factor DROP PRIMARY KEY, ADD PRIMARY KEY (workspace_id, sku);
ALTER TABLE wm_ads_forecast_factor_daily_parent DROP PRIMARY KEY, ADD PRIMARY KEY (workspace_id, parent_sku);
ALTER TABLE wm_ads_transit_channel_lead_time DROP PRIMARY KEY, ADD PRIMARY KEY (workspace_id, channel_name);
ALTER TABLE wm_ads_replenishment_decision DROP PRIMARY KEY, ADD PRIMARY KEY (workspace_id, parent_sku);
ALTER TABLE wm_ads_parent_lifecycle DROP PRIMARY KEY, ADD PRIMARY KEY (workspace_id, parent_sku);
ALTER TABLE wm_ads_campaign_decision_config DROP PRIMARY KEY, ADD PRIMARY KEY (workspace_id, campaign_type);
ALTER TABLE wm_ads_lx_item_sku_alias DROP PRIMARY KEY, ADD PRIMARY KEY (workspace_id, item_id);
ALTER TABLE wm_ads_forecast_setting DROP PRIMARY KEY, ADD PRIMARY KEY (workspace_id, id);
ALTER TABLE wm_ads_price_baseline DROP PRIMARY KEY, ADD PRIMARY KEY (workspace_id, sku);

ALTER TABLE wm_ads_import_file DROP INDEX uk_import_file_hash_type_date,
    ADD UNIQUE KEY uk_import_file_hash_type_workspace (workspace_id, sha256, file_type, report_date);
ALTER TABLE wm_ads_ai_analysis DROP INDEX uk_ai_fingerprint,
    ADD UNIQUE KEY uk_ai_fingerprint_workspace (workspace_id, data_fingerprint);
ALTER TABLE wm_ads_wfs_storage_fee DROP INDEX uk_wfs_storage_period_sku,
    ADD UNIQUE KEY uk_wfs_storage_period_sku_workspace (workspace_id, period_start, sku);
ALTER TABLE wm_ads_wfs_fulfillment_fee DROP INDEX uk_wfs_ful_ref,
    ADD UNIQUE KEY uk_wfs_ful_ref_workspace (workspace_id, wfs_reference_id);
ALTER TABLE wm_ads_lx_ad_report_raw DROP INDEX uk_lx_ad_report,
    ADD UNIQUE KEY uk_lx_ad_report_workspace (workspace_id, report_date, advertiser_id, ad_type, report_level, entity_key);
ALTER TABLE wm_ads_lx_ad_slice DROP INDEX uk_lx_ad_slice,
    ADD UNIQUE KEY uk_lx_ad_slice_workspace (workspace_id, report_date, advertiser_id, ad_type, slice_type, entity_key);
ALTER TABLE wm_ads_lx_comment_raw DROP INDEX uk_lx_comment,
    ADD UNIQUE KEY uk_lx_comment_workspace (workspace_id, platform, comment_id);
ALTER TABLE wm_ads_supply_production_order DROP INDEX uk_supply_production_source,
    ADD UNIQUE KEY uk_supply_production_source_workspace (workspace_id, source_key);
ALTER TABLE wm_ads_supply_delivery_detail DROP INDEX uk_supply_delivery_source,
    ADD UNIQUE KEY uk_supply_delivery_source_workspace (workspace_id, source_key);

-- SELECT * views were expanded before workspace_id existed; rebuild them and make catalog filters tenant-local.
CREATE OR REPLACE ALGORITHM=MERGE VIEW wm_ads_lx_ad_report AS
SELECT *
  FROM wm_ads_lx_ad_report_raw r
 WHERE r.parent_sku IS NULL
    OR r.parent_sku = ''
    OR r.parent_sku IN (SELECT p.parent_sku
                          FROM wm_ads_product_record p
                         WHERE p.workspace_id = r.workspace_id
                           AND p.current_version = 1);

CREATE OR REPLACE ALGORITHM=MERGE VIEW wm_ads_order_record_valid AS
SELECT *
  FROM wm_ads_order_record o
 WHERE o.parent_sku IN (SELECT p.parent_sku
                          FROM wm_ads_product_record p
                         WHERE p.workspace_id = o.workspace_id
                           AND p.current_version = 1);

-- The unified inventory view must carry the discriminator for tenant-scoped SQL.
CREATE OR REPLACE VIEW wm_ads_inventory_unified AS
SELECT 'LINGXING' AS source_type,
       n.workspace_id AS workspace_id,
       n.snapshot_at AS snapshot_at,
       n.sku AS sku,
       n.parent_sku AS parent_sku,
       n.item_id AS item_id,
       n.item_name AS item_name,
       n.listing_status AS status,
       n.listing_status AS publishing_status,
       n.brand AS brand,
       n.available_units AS available_units,
       n.wfs_available AS wfs_available,
       n.non_wfs_available AS non_wfs_available,
       NULL AS daily_units_sold,
       NULL AS reserved_units,
       NULL AS suggested_units,
       NULL AS surplus_units,
       NULL AS forecasted_days_supply,
       NULL AS out_of_stock_date,
       NULL AS sell_through_rate,
       NULL AS item_lifecycle,
       NULL AS inbound_units
  FROM wm_ads_lx_inventory_snapshot n;
