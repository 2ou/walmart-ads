ALTER TABLE wm_ads_import_file
    ADD COLUMN report_kind VARCHAR(40) NULL AFTER file_type;

UPDATE wm_ads_import_file
   SET report_kind = CASE
       WHEN LOWER(file_name) LIKE 'itemkeywordperformance%.csv' THEN 'ITEM_KEYWORD_PERFORMANCE'
       WHEN LOWER(file_name) LIKE 'itemperformance%.csv' THEN 'ITEM_PERFORMANCE'
       WHEN LOWER(file_name) LIKE 'keywordperformance%.csv' THEN 'KEYWORD_PERFORMANCE'
       ELSE NULL
   END
 WHERE LOWER(file_name) LIKE 'itemkeywordperformance%.csv'
    OR LOWER(file_name) LIKE 'itemperformance%.csv'
    OR LOWER(file_name) LIKE 'keywordperformance%.csv';

UPDATE wm_ads_import_file
   SET file_type = 'AD_REPORT'
 WHERE report_kind IS NOT NULL;

UPDATE wm_ads_ad_performance
   SET ad_type = CASE
       WHEN LOWER(campaign_type) LIKE '%(auto)%'
         OR LOWER(campaign_type) LIKE '%automatic%' THEN 'AD_AUTO'
       WHEN LOWER(campaign_type) LIKE '%(manual)%'
         OR LOWER(campaign_type) LIKE '%keyword bidded%' THEN 'AD_MANUAL'
       ELSE ad_type
   END;

ALTER TABLE wm_ads_import_file
    ADD INDEX idx_import_report_kind_date (report_kind, report_date, active);
