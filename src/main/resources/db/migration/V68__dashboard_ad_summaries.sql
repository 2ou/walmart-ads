-- Materialized advertising summaries keep dashboard reads away from the large raw JSON report table.
CREATE TABLE wm_ads_dashboard_ad_daily (
    workspace_id BIGINT NOT NULL,
    report_date DATE NOT NULL,
    impressions DECIMAL(24,6) NOT NULL DEFAULT 0,
    clicks DECIMAL(24,6) NOT NULL DEFAULT 0,
    ad_spend DECIMAL(24,6) NOT NULL DEFAULT 0,
    attributed_orders DECIMAL(24,6) NOT NULL DEFAULT 0,
    attributed_sales DECIMAL(24,6) NOT NULL DEFAULT 0,
    refreshed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id, report_date),
    CONSTRAINT fk_dashboard_ad_daily_workspace FOREIGN KEY (workspace_id)
        REFERENCES wm_ads_workspace(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE wm_ads_dashboard_ad_sku_daily (
    workspace_id BIGINT NOT NULL,
    report_date DATE NOT NULL,
    parent_sku VARCHAR(255) NOT NULL,
    impressions DECIMAL(24,6) NOT NULL DEFAULT 0,
    clicks DECIMAL(24,6) NOT NULL DEFAULT 0,
    ad_spend DECIMAL(24,6) NOT NULL DEFAULT 0,
    attributed_orders DECIMAL(24,6) NOT NULL DEFAULT 0,
    attributed_sales DECIMAL(24,6) NOT NULL DEFAULT 0,
    refreshed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id, report_date, parent_sku),
    KEY idx_dashboard_ad_sku_parent_date (workspace_id, parent_sku, report_date),
    CONSTRAINT fk_dashboard_ad_sku_workspace FOREIGN KEY (workspace_id)
        REFERENCES wm_ads_workspace(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE wm_ads_dashboard_ad_campaign_daily (
    workspace_id BIGINT NOT NULL,
    report_date DATE NOT NULL,
    campaign_name VARCHAR(255) NOT NULL,
    campaign_id VARCHAR(64) NULL,
    ad_type VARCHAR(32) NOT NULL,
    targeting_type VARCHAR(64) NOT NULL DEFAULT '',
    parent_sku VARCHAR(255) NOT NULL,
    impressions DECIMAL(24,6) NOT NULL DEFAULT 0,
    clicks DECIMAL(24,6) NOT NULL DEFAULT 0,
    ad_spend DECIMAL(24,6) NOT NULL DEFAULT 0,
    attributed_orders DECIMAL(24,6) NOT NULL DEFAULT 0,
    attributed_sales DECIMAL(24,6) NOT NULL DEFAULT 0,
    average_bid DECIMAL(24,6) NOT NULL DEFAULT 0,
    listed_products INT NOT NULL DEFAULT 0,
    refreshed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id, report_date, campaign_name, ad_type, targeting_type),
    KEY idx_dashboard_ad_campaign_date (workspace_id, campaign_name, report_date),
    CONSTRAINT fk_dashboard_ad_campaign_workspace FOREIGN KEY (workspace_id)
        REFERENCES wm_ads_workspace(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO wm_ads_dashboard_ad_daily (
    workspace_id, report_date, impressions, clicks, ad_spend, attributed_orders, attributed_sales
)
SELECT ads.workspace_id, ads.report_date,
       SUM(ads.impressions), SUM(ads.clicks), SUM(ads.ad_spend),
       SUM(ads.attributed_orders), SUM(ads.attributed_sales)
  FROM wm_ads_lx_ad_report_raw ads
  LEFT JOIN (
      SELECT DISTINCT workspace_id, parent_sku
        FROM wm_ads_product_record
       WHERE current_version = TRUE AND parent_sku IS NOT NULL AND parent_sku <> ''
  ) product ON product.workspace_id = ads.workspace_id AND product.parent_sku = ads.parent_sku
 WHERE ads.report_level = 'AD_ITEM'
   AND (ads.parent_sku IS NULL OR ads.parent_sku = '' OR product.parent_sku IS NOT NULL)
 GROUP BY ads.workspace_id, ads.report_date;

INSERT INTO wm_ads_dashboard_ad_sku_daily (
    workspace_id, report_date, parent_sku, impressions, clicks, ad_spend,
    attributed_orders, attributed_sales
)
SELECT ads.workspace_id, ads.report_date, product.parent_sku,
       SUM(ads.impressions), SUM(ads.clicks), SUM(ads.ad_spend),
       SUM(ads.attributed_orders), SUM(ads.attributed_sales)
  FROM wm_ads_lx_ad_report_raw ads
  JOIN (
      SELECT DISTINCT workspace_id, parent_sku
        FROM wm_ads_product_record
       WHERE current_version = TRUE AND parent_sku IS NOT NULL AND parent_sku <> ''
  ) product ON product.workspace_id = ads.workspace_id AND product.parent_sku = ads.parent_sku
 WHERE ads.report_level = 'AD_ITEM'
 GROUP BY ads.workspace_id, ads.report_date, product.parent_sku;

INSERT INTO wm_ads_dashboard_ad_campaign_daily (
    workspace_id, report_date, campaign_name, campaign_id, ad_type, targeting_type, parent_sku,
    impressions, clicks, ad_spend, attributed_orders, attributed_sales, average_bid, listed_products
)
SELECT ads.workspace_id, ads.report_date, COALESCE(ads.campaign_name, '未命名广告'),
       MAX(ads.campaign_id), COALESCE(ads.ad_type, 'UNKNOWN'), COALESCE(ads.targeting_type, ''),
       COALESCE(MAX(NULLIF(ads.parent_sku, '')), '未映射SKU'),
       SUM(ads.impressions), SUM(ads.clicks), SUM(ads.ad_spend),
       SUM(ads.attributed_orders), SUM(ads.attributed_sales),
       CASE WHEN SUM(ads.clicks) > 0 THEN SUM(ads.ad_spend) / SUM(ads.clicks)
            ELSE COALESCE(AVG(ads.bid), 0) END,
       COUNT(DISTINCT ads.item_id)
  FROM wm_ads_lx_ad_report_raw ads
  LEFT JOIN (
      SELECT DISTINCT workspace_id, parent_sku
        FROM wm_ads_product_record
       WHERE current_version = TRUE AND parent_sku IS NOT NULL AND parent_sku <> ''
  ) product ON product.workspace_id = ads.workspace_id AND product.parent_sku = ads.parent_sku
 WHERE ads.report_level = 'AD_ITEM'
   AND (ads.parent_sku IS NULL OR ads.parent_sku = '' OR product.parent_sku IS NOT NULL)
 GROUP BY ads.workspace_id, ads.report_date, COALESCE(ads.campaign_name, '未命名广告'),
          COALESCE(ads.ad_type, 'UNKNOWN'), COALESCE(ads.targeting_type, '');
