-- The normalized facts retain raw JSON payloads, so full table scans are expensive even
-- at moderate row counts.  These access paths keep dashboard aggregation on compact
-- secondary indexes instead of repeatedly scanning the JSON-bearing clustered rows.
ALTER TABLE wm_ads_lx_ad_report
    ADD INDEX idx_lx_ad_report_level_date_type (report_level, report_date, ad_type),
    ADD INDEX idx_lx_ad_report_source_date (source, report_date, report_level, item_id);

ALTER TABLE wm_ads_lx_ad_slice
    ADD INDEX idx_lx_ad_slice_type_date_type (slice_type, report_date, ad_type);
