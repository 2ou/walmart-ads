-- 项目内置数据连接器：会话加密落库、来源独立状态、30 天运行审计。
CREATE TABLE wm_ads_connector_session (
    workspace_id BIGINT NOT NULL,
    system_code VARCHAR(32) NOT NULL,
    encrypted_state MEDIUMTEXT NULL,
    encrypted_credential MEDIUMTEXT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'LOGIN_REQUIRED',
    last_login_at DATETIME(6) NULL,
    last_validated_at DATETIME(6) NULL,
    last_message VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id, system_code),
    CONSTRAINT fk_connector_session_workspace FOREIGN KEY (workspace_id)
        REFERENCES wm_ads_workspace(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目内置连接器的加密浏览器会话';

CREATE TABLE wm_ads_connector_source_state (
    workspace_id BIGINT NOT NULL,
    source_code VARCHAR(40) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    status VARCHAR(24) NOT NULL DEFAULT 'WAITING',
    last_started_at DATETIME(6) NULL,
    last_success_at DATETIME(6) NULL,
    last_failure_at DATETIME(6) NULL,
    last_message VARCHAR(1000) NULL,
    last_record_count INT NOT NULL DEFAULT 0,
    last_triggered_by VARCHAR(32) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id, source_code),
    KEY idx_connector_source_status (status, last_success_at),
    CONSTRAINT fk_connector_source_workspace FOREIGN KEY (workspace_id)
        REFERENCES wm_ads_workspace(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目内置连接器各数据源状态';

CREATE TABLE wm_ads_connector_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    source_code VARCHAR(40) NOT NULL,
    triggered_by VARCHAR(32) NOT NULL,
    status VARCHAR(24) NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    record_count INT NOT NULL DEFAULT 0,
    message VARCHAR(2000) NULL,
    PRIMARY KEY (id),
    KEY idx_connector_run_workspace_time (workspace_id, started_at),
    KEY idx_connector_run_cleanup (started_at),
    CONSTRAINT fk_connector_run_workspace FOREIGN KEY (workspace_id)
        REFERENCES wm_ads_workspace(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='项目内置连接器运行审计（保留 30 天）';

INSERT INTO wm_ads_connector_source_state (workspace_id, source_code, enabled, status)
SELECT id, 'LINGXING_INVENTORY', 1, 'WAITING' FROM wm_ads_workspace WHERE status='ACTIVE';
INSERT INTO wm_ads_connector_source_state (workspace_id, source_code, enabled, status)
SELECT id, 'LINGXING_WFS', 1, 'WAITING' FROM wm_ads_workspace WHERE status='ACTIVE';
INSERT INTO wm_ads_connector_source_state (workspace_id, source_code, enabled, status)
SELECT id, 'SUPPLY_CHAIN', supply_chain_enabled, 'WAITING' FROM wm_ads_workspace WHERE status='ACTIVE';

-- 按确认的切换策略立即清除旧插件/客户端快照；上传兜底数据不删除。
DELETE FROM wm_ads_lx_domestic_inventory;
DELETE FROM wm_ads_lx_domestic_inventory_sync_log;
DELETE FROM wm_ads_shipment_record WHERE source_type='LINGXING_BROWSER';
DELETE FROM wm_ads_lx_wfs_shipment_sync_log;
DELETE FROM wm_ads_supply_order;
DELETE FROM wm_ads_supply_production_order;
DELETE FROM wm_ads_supply_delivery_detail;
DELETE FROM wm_ads_supply_logistics_register;
DELETE FROM wm_ads_supply_chain_sync_log;

DROP TABLE wm_ads_supply_chain_bridge_log;
DROP TABLE wm_ads_supply_chain_bridge;
