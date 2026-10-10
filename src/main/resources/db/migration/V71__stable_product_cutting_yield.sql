-- Cutting yield is a manually maintained business setting, not a versioned workbook field.
-- Keep one durable value per workspace/style so replacing product workbook versions cannot erase it.
CREATE TABLE wm_ads_product_manual_setting (
    workspace_id BIGINT NOT NULL,
    parent_sku VARCHAR(80) NOT NULL,
    cutting_yield INT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workspace_id, parent_sku),
    CONSTRAINT fk_product_manual_setting_workspace
        FOREIGN KEY (workspace_id) REFERENCES wm_ads_workspace(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Prefer a non-null value on the current product version. If it has already been lost during a
-- workbook replacement, recover the most recently maintained value from an inactive version.
INSERT INTO wm_ads_product_manual_setting (workspace_id, parent_sku, cutting_yield)
SELECT workspace_id, parent_sku, cutting_yield
  FROM (
        SELECT p.workspace_id,
               p.parent_sku,
               p.cutting_yield,
               ROW_NUMBER() OVER (
                   PARTITION BY p.workspace_id, p.parent_sku
                   ORDER BY p.current_version DESC, p.created_at DESC, p.id DESC
               ) AS preference_rank
          FROM wm_ads_product_record p
         WHERE p.parent_sku IS NOT NULL
           AND p.parent_sku <> ''
           AND p.cutting_yield IS NOT NULL
           AND p.cutting_yield >= 0
       ) ranked
 WHERE preference_rank = 1;

-- Repair current rows now so all existing read paths immediately see the restored setting.
UPDATE wm_ads_product_record p
JOIN wm_ads_product_manual_setting s
  ON s.workspace_id = p.workspace_id
 AND s.parent_sku = p.parent_sku
   SET p.cutting_yield = s.cutting_yield
 WHERE p.current_version = 1
   AND p.cutting_yield IS NULL;
