-- Login identity is the immutable LingXing store name. The former free-form alias
-- becomes a short, immutable store abbreviation and is also the default SKU prefix.
ALTER TABLE wm_ads_workspace
    CHANGE COLUMN store_alias store_abbreviation VARCHAR(12) NULL,
    ADD UNIQUE KEY uk_workspace_store_abbreviation (store_abbreviation);

-- Recovery codes and forgot-password flows were intentionally retired. PINKSIR
-- provides account resets; its own emergency reset is loopback-only.
DROP TABLE wm_ads_workspace_recovery_code;
