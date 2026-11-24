-- Preserve enabled switches and stagger minutes; domestic warehouse uses its own six-hour schedule.
UPDATE wm_ads_workspace_sync_setting
   SET cadence = 'HOURLY'
 WHERE sync_category IN ('PRODUCTS', 'INVENTORY', 'SALES')
   AND cadence = 'SIX_HOURLY';
