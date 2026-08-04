-- 路途库存不再使用沃尔玛库存表的 inbound_units，而是以货件明细为准。
CREATE TABLE wm_ads_shipment_record (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    import_file_id BIGINT NOT NULL,
    current_version BOOLEAN NOT NULL DEFAULT TRUE,
    shipment_no VARCHAR(120) NOT NULL,
    local_status VARCHAR(80) NOT NULL,
    shipment_created_at DATETIME NULL,
    sku VARCHAR(180) NOT NULL,
    parent_sku VARCHAR(80) NULL,
    receipt_difference DECIMAL(18,4) NOT NULL DEFAULT 0,
    raw_payload JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_shipment_record_file
        FOREIGN KEY (import_file_id) REFERENCES wm_ads_import_file(id),
    INDEX idx_shipment_record_current_sku (current_version, sku),
    INDEX idx_shipment_record_no (shipment_no, current_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='货件明细；仅保留入库中、已发货、已申报的路途库存行';

CREATE TABLE wm_ads_shipment_logistics (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    import_file_id BIGINT NOT NULL,
    current_version BOOLEAN NOT NULL DEFAULT TRUE,
    shipment_no VARCHAR(120) NOT NULL,
    forwarder VARCHAR(120) NULL,
    channel_name VARCHAR(300) NULL,
    actual_departure_date DATE NULL,
    signed_date DATE NULL,
    signed_lead_days DECIMAL(10,2) NULL,
    raw_payload JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_shipment_logistics_file
        FOREIGN KEY (import_file_id) REFERENCES wm_ads_import_file(id),
    INDEX idx_shipment_logistics_current_no (current_version, shipment_no),
    INDEX idx_shipment_logistics_channel (channel_name, current_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='物流信息登记表；按货件号关联渠道与货代';

CREATE TABLE wm_ads_transit_channel_lead_time (
    channel_name VARCHAR(300) NOT NULL,
    lead_days DECIMAL(10,2) NULL,
    manual_override BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (channel_name),
    INDEX idx_transit_channel_updated (updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='渠道路途时效；有签收历史自动匹配，新渠道由用户维护';
