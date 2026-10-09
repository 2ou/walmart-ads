-- Retired manual-import tables have been fully superseded by the normalized LingXing tables.
-- V50/V51 copied their historical metrics before archiving them under _deprecated_* names.
-- Advertising raw payload JSON is not read by any runtime query; normalized columns remain canonical.

DROP VIEW wm_ads_lx_ad_report;

ALTER TABLE wm_ads_lx_ad_report_raw DROP COLUMN raw_payload;
ALTER TABLE wm_ads_lx_ad_slice DROP COLUMN raw_payload;

CREATE ALGORITHM=MERGE VIEW wm_ads_lx_ad_report AS
SELECT *
  FROM wm_ads_lx_ad_report_raw r
 WHERE r.parent_sku IS NULL
    OR r.parent_sku = ''
    OR r.parent_sku IN (
        SELECT p.parent_sku
          FROM wm_ads_product_record p
         WHERE p.workspace_id = r.workspace_id
           AND p.current_version = TRUE
    );

DROP TABLE _deprecated_wm_ads_ad_campaign_item_snapshot;
DROP TABLE _deprecated_wm_ads_ad_campaign_snapshot;
DROP TABLE _deprecated_wm_ads_ad_performance;
DROP TABLE _deprecated_wm_ads_inventory_snapshot;

-- Remove import metadata that no surviving business record references. This covers the retired
-- AD_REPORT/CAMPAIGN_SNAPSHOT/INVENTORY/WEEKLY_INVENTORY sources and inactive superseded files.
DELETE file
  FROM wm_ads_import_file file
 WHERE NOT EXISTS (SELECT 1 FROM wm_ads_order_record row_data WHERE row_data.import_file_id = file.id)
   AND NOT EXISTS (SELECT 1 FROM wm_ads_product_record row_data WHERE row_data.import_file_id = file.id)
   AND NOT EXISTS (SELECT 1 FROM wm_ads_return_order row_data WHERE row_data.import_file_id = file.id)
   AND NOT EXISTS (SELECT 1 FROM wm_ads_demand_forecast row_data WHERE row_data.import_file_id = file.id)
   AND NOT EXISTS (SELECT 1 FROM wm_ads_domestic_inventory_snapshot row_data WHERE row_data.import_file_id = file.id)
   AND NOT EXISTS (SELECT 1 FROM wm_ads_production_order row_data WHERE row_data.import_file_id = file.id)
   AND NOT EXISTS (SELECT 1 FROM wm_ads_shipment_record row_data WHERE row_data.import_file_id = file.id)
   AND NOT EXISTS (SELECT 1 FROM wm_ads_shipment_logistics row_data WHERE row_data.import_file_id = file.id)
   AND NOT EXISTS (SELECT 1 FROM wm_ads_sku_mapping row_data WHERE row_data.import_file_id = file.id);

DELETE batch
  FROM wm_ads_import_batch batch
 WHERE NOT EXISTS (SELECT 1 FROM wm_ads_import_file file WHERE file.batch_id = batch.id);

DELETE FROM wm_ads_workspace_session
 WHERE revoked_at IS NOT NULL OR expires_at < NOW(6);

UPDATE wm_ads_lx_ad_sync_log
   SET status = 'FAILED',
       finished_at = COALESCE(finished_at, NOW()),
       error_message = COALESCE(error_message, '服务重启中断，已由数据库清理任务关闭')
 WHERE status = 'RUNNING';
