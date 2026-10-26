-- Advertising diagnosis is tenant-scoped and repeatedly resolves the newest configuration
-- snapshot plus a short AD_ITEM performance window. Prefix the access paths with workspace_id
-- so additional stores do not make those reads scan another tenant's rows.
ALTER TABLE wm_ads_lx_ad_report_raw
    ADD INDEX idx_lx_ad_workspace_level_date (
        workspace_id,
        report_level,
        report_date
    ),
    ADD INDEX idx_lx_ad_workspace_snapshot_entity (
        workspace_id,
        report_level,
        advertiser_id,
        ad_type,
        report_date,
        campaign_id,
        item_id
    );
