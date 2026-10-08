-- Historical Walmart Item IDs can remain in disabled ad entities after a listing is replaced.
-- Keep those one-to-one reconciliations in the new LingXing model so live sync never reads
-- the retired manual advertising tables.

CREATE TABLE wm_ads_lx_item_sku_alias (
    item_id                 VARCHAR(64)     NOT NULL,
    sku                     VARCHAR(180)    NOT NULL,
    parent_sku              VARCHAR(80)     NOT NULL,
    mapping_source          VARCHAR(64)     NOT NULL,
    evidence                VARCHAR(1000),
    created_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (item_id),
    INDEX idx_lx_item_sku_alias_sku (sku),
    INDEX idx_lx_item_sku_alias_parent (parent_sku)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO wm_ads_lx_item_sku_alias (
    item_id, sku, parent_sku, mapping_source, evidence
) VALUES (
    '3853544706',
    'PNK366067-C-Blue Gray-XL-WFS02',
    'PNK366067',
    'RETIRED_ITEM_RECONCILIATION',
    'LingXing disabled ad item title identifies PNK366067 Blue Gray XL; current product mapping has a single matching SKU.'
);
