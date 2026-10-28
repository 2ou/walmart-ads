CREATE TABLE wm_ads_browser_bridge (
    workspace_id BIGINT NOT NULL,
    bridge_key_hash CHAR(64) NOT NULL,
    extension_id VARCHAR(160) NULL,
    store_abbreviation VARCHAR(64) NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'WAITING',
    connected_at DATETIME(6) NULL,
    last_seen_at DATETIME(6) NULL,
    last_message VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id),
    UNIQUE KEY uk_browser_bridge_key_hash (bridge_key_hash),
    KEY idx_browser_bridge_seen (last_seen_at),
    CONSTRAINT fk_browser_bridge_workspace FOREIGN KEY (workspace_id)
        REFERENCES wm_ads_workspace(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='当前 Chrome 数据连接桥；密钥按店铺隔离且只保存摘要';
