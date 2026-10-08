-- V32：运营记录复盘提醒表 + PNK0079 真实调整种子数据
-- 提醒是引用运营记录的一对多独立表：一条调整可挂 3/7/14 天多个检查点。

CREATE TABLE wm_ads_operation_reminder (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    operation_id BIGINT NOT NULL,
    due_date DATE NOT NULL,
    label VARCHAR(120) NULL,
    status VARCHAR(20) NOT NULL,
    resolved_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_reminder_operation (operation_id),
    INDEX idx_reminder_due_status (due_date, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ===== 种子：PNK0079 的 T0 广告优化调整（2026-08-21）=====
-- 幂等：以 external_reference 作为去重键，重复执行不会插入第二条。

INSERT INTO wm_ads_operation_record (
    target_type, target_key, parent_sku, target_name,
    action_type, source_type, status, occurred_at,
    title, before_value, after_value, note, external_reference,
    created_at, updated_at
)
SELECT
    'PARENT_SKU', 'PNK0079', 'PNK0079', 'PNK0079 女式休闲两件套度假套装',
    'AD_OPTIMIZE', 'MANUAL', 'EXECUTED', '2026-08-21 16:00:00',
    'PNK0079 广告优化（T0 调整）',
    '自动活动 PNK0079-ZDGG-20260518：Search In-grid 0% 加价；无否定词；16 个 Item 全开；基础竞价 $0.25。近 60 天 ROAS 4.13。',
    '自动活动：Search In-grid 加价 +10%；新增 10 组否定词(Phrase+Exact)；保留 7 个 Item 开启、关闭 9 个；基础竞价统一 $0.25。新建手动精准活动 PNK0079-SDGG-20260821：3 Item、2 Exact 词、CPC $0.30。',
    'T0 调整（2026-08-21）：
1) 自动活动 PNK0079-ZDGG-20260518 Search In-grid 广告位加价 +10%（修改前 ROAS 5.20 高效搜索位）。
2) 新增 10 组否定词（Negative Phrase + Negative Exact）：under 15 / modern moments / pretty garden / trendy queen / denim / jean / capri / juniors / rose gold / plus size。
3) Item 开关：保留 7 个开启（Beige L/M/XL、Black L/M、Dark Blue XL 等库存与历史表现合适），关闭 9 个（Army Green 全系、Beige S、Black S、Dark Blue L/M/S 等）。
4) 新建手动精准活动 PNK0079-SDGG-20260821：开启 3 个 Item（Black-M、Beige-L、Black-L），2 个 Exact 词（pants set for woman / travel outfit for woman 2 piece set），CPC $0.30 起步。
复盘检查点：3 天(2026-08-24) 仅确认审核展示点击；7 天(2026-08-28) 第 7 天复盘；14 天(2026-09-04) 决定是否扩大手动精准活动。
判断线：自动 Search In-grid 新增≥10 单且 ROAS≥5 可提到 +15%，ROAS<4 恢复 0%；手动精准前 3 天点击<5 则 CPC 提到 $0.33。',
    'SEED-PNK0079-20260821',
    NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM wm_ads_operation_record WHERE external_reference = 'SEED-PNK0079-20260821'
);

-- 取该运营记录 id（无论刚插入还是已存在）
SET @pnk0079_op_id = (
    SELECT id FROM wm_ads_operation_record WHERE external_reference = 'SEED-PNK0079-20260821'
);

-- 挂 3 / 7 / 14 天三个复盘检查点，due = occurredAt + N 天
INSERT INTO wm_ads_operation_reminder (operation_id, due_date, label, status, created_at, updated_at)
SELECT @pnk0079_op_id, '2026-08-24', '3 天复盘', 'PENDING', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM wm_ads_operation_reminder WHERE operation_id = @pnk0079_op_id AND label = '3 天复盘'
);

INSERT INTO wm_ads_operation_reminder (operation_id, due_date, label, status, created_at, updated_at)
SELECT @pnk0079_op_id, '2026-08-28', '7 天复盘', 'PENDING', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM wm_ads_operation_reminder WHERE operation_id = @pnk0079_op_id AND label = '7 天复盘'
);

INSERT INTO wm_ads_operation_reminder (operation_id, due_date, label, status, created_at, updated_at)
SELECT @pnk0079_op_id, '2026-09-04', '14 天复盘', 'PENDING', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM wm_ads_operation_reminder WHERE operation_id = @pnk0079_op_id AND label = '14 天复盘'
);
