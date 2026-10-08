-- V61：价格监控模型
-- 复用现有 wm_ads_product_record.price（沃尔玛在线在售价格，每小时由领星 MCP 回写），
-- 独立快照基线 / 变动记录，不新增领星调用。
-- 变动判定：每小时读已被刷新的 price，与 last_price 比；变动幅度 ≥ change-threshold-percent（默认 1%）才落一条。
-- 异常（红）判定：单跳变动 |change_pct| > abnormal-percent（默认 20%）；否则绿（正常变化）。

-- 每 SKU 一行：监控启动基线 + 最近一次所见价格。
CREATE TABLE wm_ads_price_baseline (
    sku VARCHAR(180) NOT NULL,
    parent_sku VARCHAR(80) NULL,
    baseline_price DECIMAL(18, 6) NULL COMMENT '监控启动时的基线价',
    last_price DECIMAL(18, 6) NULL COMMENT '最近一次所见价格，下次环比对比锚点',
    first_seen_at DATETIME NULL COMMENT '首次纳入监控时间',
    last_seen_at DATETIME NULL COMMENT '最近一次见到该 SKU 价格的时间',
    PRIMARY KEY (sku)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '价格监控基线（每 SKU 一行）';

-- 每次检测到价格变动落一条。
CREATE TABLE wm_ads_price_change (
    id BIGINT NOT NULL AUTO_INCREMENT,
    sku VARCHAR(180) NOT NULL,
    parent_sku VARCHAR(80) NULL,
    old_price DECIMAL(18, 6) NULL COMMENT '变动前价格',
    new_price DECIMAL(18, 6) NULL COMMENT '变动后价格',
    change_pct DECIMAL(10, 4) NULL COMMENT '环比变动%（(新-旧)/旧*100）',
    cumulative_pct DECIMAL(10, 4) NULL COMMENT '累计偏离基线%（(新-基线)/基线*100）',
    detected_at DATETIME NOT NULL COMMENT '检测到变动的时间',
    acknowledged TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否已处理（仅红色异常需手动确认）',
    abnormal TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否异常（|change_pct| > abnormal-percent）',
    PRIMARY KEY (id),
    INDEX idx_price_change_parent (parent_sku, detected_at),
    INDEX idx_price_change_sku (sku, detected_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '价格变动记录';
