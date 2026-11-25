-- Add the WFS cargo task without altering existing data, ETA overrides, or other sync plans.
INSERT INTO wm_ads_workspace_sync_setting (workspace_id, sync_category, enabled, cadence, stagger_minute)
SELECT w.id, 'WFS_SHIPMENTS', TRUE, 'SIX_HOURLY', 15
  FROM wm_ads_workspace w
 WHERE NOT EXISTS (
       SELECT 1 FROM wm_ads_workspace_sync_setting s
        WHERE s.workspace_id = w.id AND s.sync_category = 'WFS_SHIPMENTS'
 );
