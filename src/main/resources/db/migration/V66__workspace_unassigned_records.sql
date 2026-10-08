CREATE TABLE wm_ads_workspace_unassigned_record (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_key VARCHAR(500) NULL,
    identifiers VARCHAR(2000) NULL,
    reason VARCHAR(1000) NOT NULL,
    quarantined_path VARCHAR(1000) NULL,
    raw_payload LONGTEXT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_unassigned_workspace_status (workspace_id, status, created_at),
    CONSTRAINT fk_unassigned_workspace FOREIGN KEY (workspace_id)
        REFERENCES wm_ads_workspace(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
