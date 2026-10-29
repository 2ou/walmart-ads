CREATE TABLE wm_ads_browser_bridge_key (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    bridge_key_hash CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_seen_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_browser_bridge_key_ring_hash (bridge_key_hash),
    KEY idx_browser_bridge_key_workspace_created (workspace_id, created_at),
    CONSTRAINT fk_browser_bridge_key_workspace FOREIGN KEY (workspace_id)
        REFERENCES wm_ads_workspace(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='当前 Chrome 桥接令牌环；允许同一店铺的多个工作台标签页并存';

INSERT INTO wm_ads_browser_bridge_key(workspace_id, bridge_key_hash, created_at, last_seen_at)
SELECT workspace_id, bridge_key_hash, COALESCE(created_at, CURRENT_TIMESTAMP(6)), last_seen_at
  FROM wm_ads_browser_bridge;
