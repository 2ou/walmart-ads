-- Each workspace may point directory scanning at its own server-local source tree.
ALTER TABLE wm_ads_workspace
    ADD COLUMN local_import_path VARCHAR(1000) NULL AFTER sku_prefixes;
