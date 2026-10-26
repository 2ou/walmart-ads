-- 浏览器配对桥：只持久化配对码/桥接密钥摘要，不保存供应链 Bearer 凭证。
CREATE TABLE wm_ads_supply_chain_bridge (
    workspace_id BIGINT NOT NULL,
    pair_code_hash CHAR(64) NULL,
    pair_code_expires_at DATETIME(6) NULL,
    bridge_key_hash CHAR(64) NULL,
    extension_id VARCHAR(160) NULL,
    paired_at DATETIME(6) NULL,
    credential_received_at DATETIME(6) NULL,
    last_seen_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id),
    UNIQUE KEY uk_supply_bridge_key_hash (bridge_key_hash),
    KEY idx_supply_bridge_pair_code (pair_code_hash, pair_code_expires_at),
    CONSTRAINT fk_supply_bridge_workspace FOREIGN KEY (workspace_id)
        REFERENCES wm_ads_workspace(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应链浏览器配对桥（不落原始凭证）';

CREATE TABLE wm_ads_supply_chain_bridge_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NULL,
    event_type VARCHAR(48) NOT NULL,
    success TINYINT(1) NOT NULL,
    extension_id VARCHAR(160) NULL,
    message VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_supply_bridge_log_workspace_time (workspace_id, created_at),
    CONSTRAINT fk_supply_bridge_log_workspace FOREIGN KEY (workspace_id)
        REFERENCES wm_ads_workspace(id) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='供应链浏览器配对桥审计';
