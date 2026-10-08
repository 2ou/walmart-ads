-- The LingXing MCP remains the sole source from its first synchronized day onward.
-- Older HISTORY workbook rows are copied once into the normalized fact table so that
-- long-range analysis stays continuous without retaining a runtime dependency on the
-- retired importer.  Overlapping AD_REPORT rows are intentionally not migrated.

-- The item snapshot was the last legacy advertising table without an archive name.
RENAME TABLE wm_ads_ad_campaign_item_snapshot TO _deprecated_wm_ads_ad_campaign_item_snapshot;

-- Prefer the newest SKU observed by LingXing when an Item changed seller SKU.  The raw
-- payload is retained, so the original value remains auditable after canonicalization.
UPDATE wm_ads_lx_ad_report report
JOIN (
    SELECT item_id, sku AS latest_sku
      FROM (
          SELECT item_id, sku,
                 ROW_NUMBER() OVER (PARTITION BY item_id ORDER BY report_date DESC, id DESC) AS row_rank
            FROM wm_ads_lx_ad_report
           WHERE source = 'LINGXING_MCP'
             AND report_level = 'AD_ITEM'
             AND item_id IS NOT NULL
             AND sku IS NOT NULL
      ) ranked
     WHERE row_rank = 1
) latest ON latest.item_id = report.item_id
   SET report.sku = latest.latest_sku,
       report.parent_sku = SUBSTRING_INDEX(latest.latest_sku, '-', 1)
 WHERE report.source = 'LINGXING_MCP'
   AND report.report_level = 'AD_ITEM';

-- Keep the retired-item fallback aligned with the canonical SKU learned from LingXing.
UPDATE wm_ads_lx_item_sku_alias alias_row
JOIN (
    SELECT item_id, sku AS latest_sku
      FROM (
          SELECT item_id, sku,
                 ROW_NUMBER() OVER (PARTITION BY item_id ORDER BY report_date DESC, id DESC) AS row_rank
            FROM wm_ads_lx_ad_report
           WHERE source = 'LINGXING_MCP'
             AND report_level = 'AD_ITEM'
             AND item_id IS NOT NULL
             AND sku IS NOT NULL
      ) ranked
     WHERE row_rank = 1
) latest ON latest.item_id = alias_row.item_id
   SET alias_row.sku = latest.latest_sku,
       alias_row.parent_sku = SUBSTRING_INDEX(latest.latest_sku, '-', 1),
       alias_row.mapping_source = 'LINGXING_CANONICAL_RECONCILIATION',
       alias_row.evidence = 'Canonicalized from the newest successful LingXing AD_ITEM report.';

INSERT INTO wm_ads_lx_ad_report (
    report_date, advertiser_id, ad_type, report_level, entity_key,
    source, attribution_days,
    campaign_id, campaign_name, campaign_type, campaign_status,
    targeting_type, bidding_strategy, daily_budget, target_roas,
    ad_group_id, ad_group_name, ad_group_status,
    item_id, ad_item_id, sku, parent_sku, ad_name, ad_status,
    review_status, review_reason,
    keyword_id, keyword_text, keyword_state, keyword_status, match_type,
    bid, suggested_bid,
    impressions, clicks, ad_spend, attributed_sales, attributed_orders,
    attributed_units, advertised_sku_sales, advertised_sku_units,
    other_sku_sales, other_sku_units,
    ntb_orders, ntb_revenue, ntb_units,
    cpc, ctr, cvr, acos, roas, aov, cpa,
    raw_payload, synced_at
)
SELECT legacy.report_date,
       0,
       'SP',
       'AD_ITEM',
       CONCAT('legacy:', legacy.id),
       'LEGACY_MIGRATION',
       14,
       legacy.campaign_id,
       legacy.campaign_name,
       legacy.ad_type,
       NULL,
       CASE WHEN legacy.ad_type = 'AD_AUTO' THEN 'AUTO' ELSE 'MANUAL' END,
       legacy.bidding_strategy,
       NULL,
       legacy.target_roas,
       legacy.ad_group_id,
       legacy.ad_group_name,
       NULL,
       legacy.item_id,
       NULL,
       COALESCE(lx.sku, old_daily.sku, sku_map.sku, product.sku, item_alias.sku),
       COALESCE(
           NULLIF(legacy.parent_sku, ''),
           lx.parent_sku,
           old_daily.parent_sku,
           sku_map.parent_sku,
           item_alias.parent_sku,
           SUBSTRING_INDEX(COALESCE(lx.sku, old_daily.sku, sku_map.sku, product.sku, item_alias.sku), '-', 1)
       ),
       legacy.item_name,
       NULL,
       NULL,
       NULL,
       NULL,
       legacy.bidded_keyword,
       NULL,
       NULL,
       legacy.match_type,
       legacy.bid,
       NULL,
       legacy.impressions,
       legacy.clicks,
       legacy.ad_spend,
       legacy.attributed_sales,
       legacy.orders,
       legacy.units_sold,
       legacy.advertised_sku_sales,
       0,
       legacy.other_sku_sales,
       0,
       0,
       0,
       0,
       legacy.average_cpc,
       legacy.ctr,
       legacy.conversion_rate,
       CASE WHEN legacy.attributed_sales > 0 THEN legacy.ad_spend / legacy.attributed_sales ELSE 0 END,
       legacy.roas,
       CASE WHEN legacy.orders > 0 THEN legacy.attributed_sales / legacy.orders ELSE 0 END,
       CASE WHEN legacy.orders > 0 THEN legacy.ad_spend / legacy.orders ELSE 0 END,
       legacy.raw_payload,
       legacy.created_at
  FROM _deprecated_wm_ads_ad_performance legacy
  JOIN wm_ads_import_file import_file ON import_file.id = legacy.import_file_id
  LEFT JOIN (
      SELECT item_id, sku, parent_sku
        FROM (
            SELECT item_id, sku, parent_sku,
                   ROW_NUMBER() OVER (PARTITION BY item_id ORDER BY report_date DESC, id DESC) AS row_rank
              FROM wm_ads_lx_ad_report
             WHERE source = 'LINGXING_MCP'
               AND report_level = 'AD_ITEM'
               AND item_id IS NOT NULL
               AND sku IS NOT NULL
        ) ranked
       WHERE row_rank = 1
  ) lx ON lx.item_id = legacy.item_id
  LEFT JOIN (
      SELECT item_id, MAX(sku) sku, MAX(parent_sku) parent_sku
        FROM _deprecated_wm_ads_ad_performance
       WHERE sku IS NOT NULL
       GROUP BY item_id
  ) old_daily ON old_daily.item_id = legacy.item_id
  LEFT JOIN (
      SELECT item_id, MAX(sku) sku, MAX(parent_sku) parent_sku
        FROM wm_ads_sku_mapping
       GROUP BY item_id
  ) sku_map ON sku_map.item_id = legacy.item_id
  LEFT JOIN (
      SELECT product_id item_id, MAX(sku) sku
        FROM wm_ads_product_record
       WHERE product_id IS NOT NULL
       GROUP BY product_id
  ) product ON product.item_id = legacy.item_id
  LEFT JOIN wm_ads_lx_item_sku_alias item_alias ON item_alias.item_id = legacy.item_id
 WHERE import_file.file_type = 'HISTORY'
   AND legacy.report_date < COALESCE(
       (SELECT MIN(report_date) FROM wm_ads_lx_ad_report WHERE source = 'LINGXING_MCP'),
       DATE '9999-12-31'
   )
   AND COALESCE(lx.sku, old_daily.sku, sku_map.sku, product.sku, item_alias.sku) IS NOT NULL;
