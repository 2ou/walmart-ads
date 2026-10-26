-- WFS Settlement / Storage 页面化导入基础设施。
-- 旧表已有历史费用数据，本迁移原位升级并保留全部旧记录。

ALTER TABLE wm_ads_wfs_storage_fee
    MODIFY COLUMN final_storage_fee DECIMAL(18,8) NOT NULL DEFAULT 0,
    MODIFY COLUMN original_amount DECIMAL(18,8) NULL,
    MODIFY COLUMN discount_savings DECIMAL(18,8) NULL,
    ADD COLUMN source_store VARCHAR(160) NULL AFTER source_file,
    ADD COLUMN match_status VARCHAR(24) NOT NULL DEFAULT 'UNMATCHED' AFTER parent_sku,
    ADD COLUMN row_hash CHAR(64) NULL AFTER workspace_id,
    ADD COLUMN raw_fields JSON NULL AFTER row_hash,
    ADD COLUMN managed_by_batches TINYINT(1) NOT NULL DEFAULT 0 AFTER raw_fields;

ALTER TABLE wm_ads_wfs_fulfillment_fee
    MODIFY COLUMN net_payable DECIMAL(18,8) NULL,
    MODIFY COLUMN original_amount DECIMAL(18,8) NULL,
    MODIFY COLUMN discount_savings DECIMAL(18,8) NULL,
    ADD COLUMN txn_at DATETIME NULL AFTER txn_date,
    ADD COLUMN source_store VARCHAR(160) NULL AFTER source_file,
    ADD COLUMN match_status VARCHAR(24) NOT NULL DEFAULT 'UNMATCHED' AFTER parent_sku,
    ADD COLUMN row_hash CHAR(64) NULL AFTER workspace_id,
    ADD COLUMN raw_fields JSON NULL AFTER row_hash,
    ADD COLUMN managed_by_batches TINYINT(1) NOT NULL DEFAULT 0 AFTER raw_fields;

-- 为历史数据生成与新导入器一致、可稳定复算的去重键。
UPDATE wm_ads_wfs_storage_fee
   SET row_hash = SHA2(CONCAT_WS(CHAR(31),
       'STORAGE', DATE_FORMAT(period_start, '%Y-%m-%d'), DATE_FORMAT(period_end, '%Y-%m-%d'),
       COALESCE(gtin, ''), COALESCE(sku, ''), COALESCE(item_id, ''), COALESCE(item_name, ''),
       COALESCE(CAST(length_in AS CHAR), ''), COALESCE(CAST(width_in AS CHAR), ''),
       COALESCE(CAST(height_in AS CHAR), ''), COALESCE(CAST(weight_lb AS CHAR), ''),
       COALESCE(CAST(std_daily_fee AS CHAR), ''), COALESCE(CAST(peak_daily_fee AS CHAR), ''),
       COALESCE(CAST(lt_daily_fee AS CHAR), ''), COALESCE(CAST(lt2_daily_fee AS CHAR), ''),
       COALESCE(CAST(std_avg_units AS CHAR), ''), COALESCE(CAST(peak_avg_units AS CHAR), ''),
       COALESCE(CAST(lt_avg_units AS CHAR), ''), COALESCE(CAST(lt2_avg_units AS CHAR), ''),
       COALESCE(CAST(days_in_period AS CHAR), ''), COALESCE(CAST(final_storage_fee AS CHAR), ''),
       COALESCE(CAST(original_amount AS CHAR), ''), COALESCE(CAST(discount_savings AS CHAR), '')
   ), 256)
 WHERE row_hash IS NULL;

UPDATE wm_ads_wfs_fulfillment_fee
   SET txn_at = CAST(txn_date AS DATETIME)
 WHERE txn_at IS NULL AND txn_date IS NOT NULL;

UPDATE wm_ads_wfs_storage_fee
   SET match_status = CASE WHEN NULLIF(TRIM(sku), '') IS NOT NULL AND NULLIF(TRIM(parent_sku), '') IS NOT NULL
                           THEN 'MATCHED' ELSE 'UNMATCHED' END;

UPDATE wm_ads_wfs_fulfillment_fee
   SET match_status = CASE WHEN NULLIF(TRIM(sku), '') IS NOT NULL AND NULLIF(TRIM(parent_sku), '') IS NOT NULL
                           THEN 'MATCHED' ELSE 'UNMATCHED' END;

UPDATE wm_ads_wfs_fulfillment_fee
   SET row_hash = CASE
       WHEN NULLIF(TRIM(wfs_reference_id), '') IS NOT NULL
           THEN SHA2(CONCAT('REF', CHAR(31), TRIM(wfs_reference_id)), 256)
       ELSE SHA2(CONCAT_WS(CHAR(31),
           'SETTLEMENT', COALESCE(order_id, ''), COALESCE(order_line, ''),
           COALESCE(po_no, ''), COALESCE(po_line, ''), COALESCE(transaction_type, ''),
           COALESCE(reason_code, ''), COALESCE(detail, ''), COALESCE(DATE_FORMAT(txn_at, '%Y-%m-%d %H:%i:%s'), ''),
           COALESCE(CAST(qty AS CHAR), ''), COALESCE(billing_method, ''), COALESCE(partner_id, ''),
           COALESCE(partner_gtin, ''), COALESCE(partner_item_name, ''), COALESCE(category, ''),
           COALESCE(sub_category, ''), COALESCE(CAST(net_payable AS CHAR), ''),
           COALESCE(customer_order_id, ''), COALESCE(order_channel_id, ''),
           COALESCE(CAST(original_amount AS CHAR), ''), COALESCE(CAST(discount_savings AS CHAR), '')
       ), 256)
   END
 WHERE row_hash IS NULL;

ALTER TABLE wm_ads_wfs_storage_fee
    MODIFY COLUMN row_hash CHAR(64) NOT NULL,
    DROP INDEX uk_wfs_storage_period_sku_workspace,
    ADD UNIQUE KEY uk_wfs_storage_row_hash (workspace_id, row_hash),
    ADD KEY idx_wfs_storage_period_fee (workspace_id, period_end, final_storage_fee),
    ADD KEY idx_wfs_storage_match (workspace_id, match_status);

ALTER TABLE wm_ads_wfs_fulfillment_fee
    MODIFY COLUMN row_hash CHAR(64) NOT NULL,
    DROP INDEX uk_wfs_ful_ref_workspace,
    ADD UNIQUE KEY uk_wfs_settlement_row_hash (workspace_id, row_hash),
    ADD KEY idx_wfs_settlement_reference (workspace_id, wfs_reference_id),
    ADD KEY idx_wfs_settlement_period_amount (workspace_id, period_end, net_payable),
    ADD KEY idx_wfs_settlement_match (workspace_id, match_status);

CREATE TABLE wm_ads_wfs_import_batch (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    report_type VARCHAR(24) NOT NULL,
    source_store VARCHAR(160) NULL,
    file_name VARCHAR(300) NOT NULL,
    file_sha256 CHAR(64) NOT NULL,
    active_file_hash CHAR(64) NULL,
    file_size BIGINT NOT NULL DEFAULT 0,
    period_start DATE NULL,
    period_end DATE NULL,
    status VARCHAR(24) NOT NULL,
    total_rows INT NOT NULL DEFAULT 0,
    imported_rows INT NOT NULL DEFAULT 0,
    duplicate_rows INT NOT NULL DEFAULT 0,
    failed_rows INT NOT NULL DEFAULT 0,
    linked_rows INT NOT NULL DEFAULT 0,
    summary_json JSON NULL,
    error_details JSON NULL,
    error_message VARCHAR(1000) NULL,
    created_by VARCHAR(64) NULL,
    imported_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    deleted_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_wfs_active_file (workspace_id, report_type, active_file_hash),
    KEY idx_wfs_batch_workspace_type_time (workspace_id, report_type, imported_at),
    KEY idx_wfs_batch_period (workspace_id, report_type, period_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='WFS 文件上传批次；仅保存文件元数据、解析结果，不保存原始文件';

CREATE TABLE wm_ads_wfs_batch_row (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workspace_id BIGINT NOT NULL,
    batch_id BIGINT NOT NULL,
    report_type VARCHAR(24) NOT NULL,
    row_id BIGINT NOT NULL,
    duplicate_on_import TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_wfs_batch_row (workspace_id, batch_id, report_type, row_id),
    KEY idx_wfs_row_batches (workspace_id, report_type, row_id),
    CONSTRAINT fk_wfs_batch_row_batch FOREIGN KEY (batch_id)
        REFERENCES wm_ads_wfs_import_batch(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
  COMMENT='WFS 导入批次与规范化费用行的多对多关联';
