-- Marketplace monitoring is shared across workspaces. Favorites are scoped to the login account.
CREATE TABLE wm_selection_monitor (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source_key CHAR(64) NOT NULL,
    source_url VARCHAR(4096) NOT NULL,
    zip_code CHAR(5) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_selection_monitor_source (source_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wm_selection_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    monitor_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    observed_at TIMESTAMP(6) NOT NULL,
    expected_pages INT NOT NULL,
    completed_pages INT NOT NULL,
    listing_complete BOOLEAN NOT NULL,
    inventory_complete BOOLEAN NOT NULL,
    declared_count INT NULL,
    issue VARCHAR(600) NULL,
    payload_hash CHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_selection_run_payload (monitor_id, business_date, payload_hash),
    KEY idx_selection_run_date (monitor_id, business_date, id),
    FOREIGN KEY (monitor_id) REFERENCES wm_selection_monitor (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wm_selection_style (
    id BIGINT NOT NULL AUTO_INCREMENT,
    monitor_id BIGINT NOT NULL,
    style_key VARCHAR(128) NOT NULL,
    group_verified BOOLEAN NOT NULL,
    first_seen DATE NOT NULL,
    canonical_style_id BIGINT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_selection_style_identity (monitor_id, style_key),
    FOREIGN KEY (monitor_id) REFERENCES wm_selection_monitor (id),
    FOREIGN KEY (canonical_style_id) REFERENCES wm_selection_style (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wm_selection_style_snapshot (
    run_id BIGINT NOT NULL,
    style_id BIGINT NOT NULL,
    item_id VARCHAR(32) NOT NULL,
    title VARCHAR(1000) NOT NULL,
    brand VARCHAR(200) NULL,
    category VARCHAR(200) NULL,
    image_url VARCHAR(4096) NULL,
    price DECIMAL(12,2) NULL,
    source_position INT NULL,
    expected_skus INT NOT NULL,
    group_verified BOOLEAN NOT NULL,
    PRIMARY KEY (run_id, style_id),
    UNIQUE KEY uq_selection_style_snapshot_item (run_id, item_id),
    KEY idx_selection_style_history (style_id, run_id),
    FOREIGN KEY (run_id) REFERENCES wm_selection_run (id),
    FOREIGN KEY (style_id) REFERENCES wm_selection_style (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wm_selection_sku_snapshot (
    run_id BIGINT NOT NULL,
    style_id BIGINT NOT NULL,
    item_id VARCHAR(32) NOT NULL,
    product_id VARCHAR(64) NULL,
    color_name VARCHAR(200) NULL,
    size_name VARCHAR(128) NULL,
    order_limit INT NULL,
    max_order_quantity INT NULL,
    availability VARCHAR(64) NULL,
    seller_id VARCHAR(128) NULL,
    offer_id VARCHAR(128) NULL,
    fulfillment VARCHAR(64) NULL,
    observed_at TIMESTAMP(6) NULL,
    PRIMARY KEY (run_id, item_id),
    KEY idx_selection_sku_member (run_id, style_id),
    FOREIGN KEY (run_id, style_id) REFERENCES wm_selection_style_snapshot (run_id, style_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wm_selection_day (
    monitor_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    run_id BIGINT NOT NULL,
    previous_run_id BIGINT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    rule_version VARCHAR(24) NOT NULL,
    PRIMARY KEY (monitor_id, business_date),
    FOREIGN KEY (monitor_id) REFERENCES wm_selection_monitor (id),
    FOREIGN KEY (run_id) REFERENCES wm_selection_run (id),
    FOREIGN KEY (previous_run_id) REFERENCES wm_selection_run (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wm_selection_daily_result (
    monitor_id BIGINT NOT NULL,
    business_date DATE NOT NULL,
    style_id BIGINT NOT NULL,
    sales BIGINT NULL,
    sales_7d BIGINT NULL,
    stock_increase_skus INT NOT NULL,
    stock_increase_units BIGINT NOT NULL,
    comparable_skus INT NOT NULL,
    expected_skus INT NOT NULL,
    overall_rank INT NULL,
    category_rank INT NULL,
    PRIMARY KEY (monitor_id, business_date, style_id),
    KEY idx_selection_daily_rank (monitor_id, business_date, overall_rank),
    KEY idx_selection_daily_history (monitor_id, style_id, business_date),
    FOREIGN KEY (monitor_id, business_date) REFERENCES wm_selection_day (monitor_id, business_date),
    FOREIGN KEY (style_id) REFERENCES wm_selection_style (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE wm_selection_watch (
    account_name VARCHAR(191) NOT NULL,
    style_id BIGINT NOT NULL,
    PRIMARY KEY (account_name, style_id),
    FOREIGN KEY (style_id) REFERENCES wm_selection_style (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
