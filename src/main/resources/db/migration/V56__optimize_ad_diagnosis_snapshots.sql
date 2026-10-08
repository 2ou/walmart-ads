-- Campaign diagnosis repeatedly resolves the latest CAMPAIGN and AD_ITEM snapshot
-- per advertiser/ad type. Keep that lookup covering so MySQL does not visit every
-- JSON-bearing fact row while calculating MAX(report_date).
ALTER TABLE wm_ads_lx_ad_report_raw
    ADD INDEX idx_lx_ad_snapshot_lookup (
        report_level,
        advertiser_id,
        ad_type,
        report_date
    );
