-- 沃尔玛销量/订单数据改为领星 MCP 同步入库（替代每日手工导出的 PO_Data 订单明细）。
--
-- 口径说明：
--   领星只有 SKU × 日的聚合值（销量/订单量/销售额），没有订单明细。入库时按「订单量」拆行
--   还原成 wm_ads_order_record 的行结构，使既有 30+ 处聚合 SQL 无需改动即可继续工作：
--     sum(quantity)              = 销量
--     sum(quantity * item_cost)  = 销售额
--     count(distinct order_number) = 店铺级订单量（当日行轮询分配 D 个合成单号）
--   合订单号为合成序号，仅用于聚合计数，不可用于订单明细追溯。
--
-- 领星下单时间窗口上限 90 天，早于该窗口的数据（2026-06-24 之前）仍由原 PO_Data 批次承载。

CREATE TABLE wm_ads_lingxing_order_sync_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    started_at DATETIME NOT NULL COMMENT '同步开始时间',
    finished_at DATETIME NULL COMMENT '同步结束时间',
    status VARCHAR(20) NOT NULL COMMENT 'RUNNING/SUCCESS/PARTIAL/FAILED',
    source VARCHAR(40) NOT NULL COMMENT '数据来源，恒为 lingxing_mcp',
    triggered_by VARCHAR(20) NOT NULL COMMENT 'CRON/MANUAL/BACKFILL',
    window_start DATE NOT NULL COMMENT '本次同步窗口起始日（含）',
    window_end DATE NOT NULL COMMENT '本次同步窗口结束日（含）',
    fetched_sku_count INT NOT NULL DEFAULT 0 COMMENT '拉到的有销 SKU 数（窗口内去重）',
    covered_day_count INT NOT NULL DEFAULT 0 COMMENT '实际入库的日期数',
    inserted_count INT NOT NULL DEFAULT 0 COMMENT '写入 wm_ads_order_record 行数',
    deleted_count INT NOT NULL DEFAULT 0 COMMENT '清理的旧领星订单行数',
    store_order_total DECIMAL(18, 4) NOT NULL DEFAULT 0 COMMENT '店铺级订单量合计（核对用真值）',
    duration_ms BIGINT NOT NULL DEFAULT 0,
    error_message TEXT NULL,
    PRIMARY KEY (id),
    INDEX idx_lingxing_order_log_started (started_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '领星 MCP 销量/订单日粒度同步落库记录';

-- 领星订单按「每个日期一个 import_file」的粒度替换。该类型文件的 sha256 是同步内容摘要，
-- 同一天重复同步时先删旧 file 再插新 file，不会撞 uk_import_file_hash_type。
-- 查询按 (file_type, report_date, active) 走既有 idx_import_file_report。
