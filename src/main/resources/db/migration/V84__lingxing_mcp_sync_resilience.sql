-- Durable LingXing MCP throttling, cooldown and synchronization observability.
-- A scope is the hash of (gateway URL + MCP key); no credential is stored in this table.
CREATE TABLE wm_ads_mcp_runtime (
    scope_hash CHAR(64) NOT NULL,
    key_fingerprint VARCHAR(20) NOT NULL,
    limited_until DATETIME(6) NULL,
    next_request_at DATETIME(6) NULL,
    last_request_at DATETIME(6) NULL,
    last_limit_at DATETIME(6) NULL,
    last_limit_message VARCHAR(1000) NULL,
    consecutive_limit_count INT NOT NULL DEFAULT 0,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (scope_hash),
    KEY idx_mcp_runtime_limited_until (limited_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE wm_ads_workspace
    ADD COLUMN initialization_last_attempt_at DATETIME(6) NULL,
    ADD COLUMN initialization_next_attempt_at DATETIME(6) NULL;

ALTER TABLE wm_ads_workspace_sync_setting
    ADD COLUMN last_valid_data_at DATETIME(6) NULL,
    ADD COLUMN last_fetched_count BIGINT NULL,
    ADD COLUMN last_written_count BIGINT NULL,
    ADD COLUMN integrity_status VARCHAR(32) NULL,
    ADD COLUMN next_retry_at DATETIME(6) NULL,
    ADD COLUMN lease_token VARCHAR(64) NULL,
    ADD COLUMN lease_until DATETIME(6) NULL,
    ADD KEY idx_workspace_sync_lease (lease_until);

CREATE TABLE wm_ads_workspace_sync_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    sync_category VARCHAR(32) NOT NULL,
    trigger_source VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    fetched_count BIGINT NULL,
    written_count BIGINT NULL,
    integrity_status VARCHAR(32) NULL,
    message VARCHAR(1000) NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    completed_at DATETIME(6) NULL,
    duration_ms BIGINT NULL,
    PRIMARY KEY (id),
    KEY idx_workspace_sync_run_recent (workspace_id, started_at),
    KEY idx_workspace_sync_run_category (workspace_id, sync_category, started_at),
    CONSTRAINT fk_workspace_sync_run_workspace FOREIGN KEY (workspace_id)
        REFERENCES wm_ads_workspace(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- Current advertising data is small and time-sensitive; history remains a separate low-priority job.
UPDATE wm_ads_workspace_sync_setting
   SET cadence='HOURLY'
 WHERE sync_category='ADS' AND cadence='SIX_HOURLY';
