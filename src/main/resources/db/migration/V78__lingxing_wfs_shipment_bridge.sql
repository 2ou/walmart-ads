-- 领星 WFS 货件列表改由浏览器连接器直接同步；原 XLSX 导入继续作为显式兜底。
-- 两种来源复用同一张业务表与 current_version 口径，最后一次成功同步的数据生效。
ALTER TABLE wm_ads_shipment_record
    MODIFY COLUMN import_file_id BIGINT NULL,
    ADD COLUMN source_type VARCHAR(32) NOT NULL DEFAULT 'FILE_UPLOAD' AFTER workspace_id,
    ADD COLUMN source_url VARCHAR(500) NULL AFTER source_type,
    ADD COLUMN synced_at DATETIME(6) NULL AFTER source_url,
    ADD KEY idx_shipment_workspace_source_current
        (workspace_id, source_type, current_version, synced_at);

UPDATE wm_ads_shipment_record
   SET source_type = 'FILE_UPLOAD',
       synced_at = COALESCE(synced_at, created_at)
 WHERE synced_at IS NULL;

CREATE TABLE wm_ads_lx_wfs_shipment_sync_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    started_at DATETIME(6) NOT NULL,
    finished_at DATETIME(6) NULL,
    status VARCHAR(20) NOT NULL,
    source_shipment_count INT NOT NULL DEFAULT 0,
    imported_shipment_count INT NOT NULL DEFAULT 0,
    imported_row_count INT NOT NULL DEFAULT 0,
    skipped_row_count INT NOT NULL DEFAULT 0,
    transit_units DECIMAL(18,4) NOT NULL DEFAULT 0,
    source_url VARCHAR(500) NULL,
    message VARCHAR(1000) NULL,
    PRIMARY KEY (id),
    KEY idx_lx_wfs_sync_workspace_finished (workspace_id, finished_at),
    CONSTRAINT fk_lx_wfs_sync_workspace
        FOREIGN KEY (workspace_id) REFERENCES wm_ads_workspace (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
