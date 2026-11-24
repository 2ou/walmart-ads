CREATE DATABASE IF NOT EXISTS walmart_inventory_reports CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE walmart_inventory_reports;

CREATE TABLE IF NOT EXISTS brands (
    slug VARCHAR(100) PRIMARY KEY,
    name VARCHAR(100) NOT NULL
);

CREATE TABLE IF NOT EXISTS styles (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    brand_slug VARCHAR(100) NOT NULL,
    item_id VARCHAR(32) NOT NULL,
    title VARCHAR(1000),
    seller VARCHAR(200),
    average_rating DECIMAL(4,2),
    review_count INT UNSIGNED,
    rating_observed_at DATETIME(6),
    metadata JSON NOT NULL,
    current_snapshot_id BIGINT UNSIGNED,
    UNIQUE KEY style_identity (brand_slug, item_id),
    FOREIGN KEY (brand_slug) REFERENCES brands(slug)
);

CREATE TABLE IF NOT EXISTS inventory_snapshots (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    style_id BIGINT UNSIGNED NOT NULL,
    captured_at DATETIME(6),
    content_hash BINARY(32) NOT NULL,
    capture_metadata JSON NOT NULL,
    UNIQUE KEY snapshot_identity (style_id, content_hash),
    KEY style_history (style_id, captured_at),
    FOREIGN KEY (style_id) REFERENCES styles(id)
);

CREATE TABLE IF NOT EXISTS inventory_skus (
    snapshot_id BIGINT UNSIGNED NOT NULL,
    ordinal INT UNSIGNED NOT NULL,
    item_id VARCHAR(32),
    product_id VARCHAR(64),
    variant_dimension VARCHAR(100),
    variant_value VARCHAR(500),
    variant_label VARCHAR(500),
    size_name VARCHAR(200),
    order_limit INT UNSIGNED,
    max_order_quantity INT UNSIGNED,
    minimum_order_quantity INT UNSIGNED,
    availability VARCHAR(64),
    price DECIMAL(12,2),
    was_price DECIMAL(12,2),
    seller VARCHAR(200),
    source_row JSON NOT NULL,
    PRIMARY KEY (snapshot_id, ordinal),
    KEY sku_history (item_id, snapshot_id),
    FOREIGN KEY (snapshot_id) REFERENCES inventory_snapshots(id)
);

CREATE TABLE IF NOT EXISTS report_pages (
    brand_slug VARCHAR(100) NOT NULL,
    filename VARCHAR(180) NOT NULL,
    kind ENUM('brand','item','edition','keyword') NOT NULL,
    title VARCHAR(1000),
    updated_at DATETIME(6) NOT NULL,
    content_hash BINARY(32) NOT NULL,
    html_gzip MEDIUMBLOB NOT NULL,
    PRIMARY KEY (brand_slug, filename),
    FOREIGN KEY (brand_slug) REFERENCES brands(slug)
);

CREATE OR REPLACE SQL SECURITY INVOKER VIEW current_inventory_skus AS
SELECT b.name AS brand, s.item_id AS style_item_id, s.title, s.average_rating,
       s.review_count, p.captured_at, k.item_id AS sku_item_id, k.product_id,
       k.variant_label, k.size_name, k.order_limit, k.max_order_quantity,
       k.availability, k.price, k.was_price, k.seller
FROM styles s
JOIN brands b ON b.slug=s.brand_slug
JOIN inventory_snapshots p ON p.id=s.current_snapshot_id
JOIN inventory_skus k ON k.snapshot_id=p.id;
