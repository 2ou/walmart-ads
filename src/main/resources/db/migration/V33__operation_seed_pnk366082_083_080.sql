-- V33：运营记录复盘提醒种子（第二批）
-- PNK366082 / PNK366083 / PNK366080 三款的 T0 广告整调，各一条 AD_OPTIMIZE 记录 + 3/7/14 天复盘检查点。
-- 幂等：以 external_reference 作为去重键，重复执行不会插入第二条。
-- 注意：MySQL 默认 || 是逻辑 OR（非字符串拼接），note 全部用多行字面量，禁止用 || 拼接。

-- ============ PNK366082（T0 = 2026-08-21）============
INSERT INTO wm_ads_operation_record (
    target_type, target_key, parent_sku, target_name,
    action_type, source_type, status, occurred_at,
    title, before_value, after_value, note, external_reference,
    created_at, updated_at
)
SELECT
    'PARENT_SKU', 'PNK366082', 'PNK366082', 'PNK366082 女式短裤两件套休闲运动套装',
    'AD_OPTIMIZE', 'MANUAL', 'EXECUTED', '2026-08-21 18:00:00',
    'PNK366082 广告优化（T0 调整）',
    '修改前近60天整体：花费 $592.62、归因销售 $2,695.79、ROAS 4.55、CVR 7.24%。主力 SDEX 精准活动 ROAS 5.25；SDPH Phrase ROAS 4.39；ZDGG 自动 ROAS 2.57；SDGF 暂停。共 4 个活动、未系统清理低效词与颜色不匹配流量。',
    'SDEX 精准：Exact 精简为 22 启用/27 停用、Bid 统一 $0.30、Search In-grid +10%、Item 扩至 10 个（新增 4 个 L 码）。SDPH Phrase：新增 4 个 L 码 Item、新增 2 个 Negative Exact、停用 4 个红色词。ZDGG 自动：新增 4 个 L 码、Brown XL 单独暂停（最终 9 enabled）、Bid 统一 $0.20、广告位全 0%、新增 15 个 Negative Phrase + 4 个 Negative Exact。',
    'T0 调整（2026-08-21）：
1) SDEX 精准活动：Exact 关键词从原配置精简为 22 启用/27 停用，Bid 统一 $0.30；Search In-grid 加价 +10%（修改前 ROAS 5.72 高效位）；新增 Dark Blue L / Brown L / Olive Green L / Black L 四个 L 码 Item，合计 10 个。
2) SDPH Phrase 活动：新增与 SDEX 相同的 4 个 L 码 Item（合计 10 enabled）；新增 Negative Exact（2 piece woman outfit / woman shorts set outfit，阻断已验证 0 订单的完整查询）；停用 4 个红色词（产品库无红色）。
3) ZDGG 自动活动：新增 4 个 L 码 Item，Brown XL 单独 paused（最终 9 enabled），Item Bid 统一 $0.20，所有广告位保持 0%；新增 15 个 Negative Phrase（junior/polka dot/camo/red/pink/purple 等）+ 4 个 Negative Exact（woman short set 等已由 SDEX 承接的核心词）。
复盘检查点：3 天(2026-08-24) 确认新 Item 审核/展示/点击，不调价；7 天(2026-08-28) 第 7 天转化复盘；14 天(2026-09-04) 季节性放量复盘。
判断线：新 Item 12-15 点击 0 单则暂停；SDEX Search In-grid 新增≥10 单且 ROAS≥5 维持 +10%、<4 恢复 0%；ZDGG 单搜索词 20 点击或 $6 花费 0 单加 Negative Exact；Brown XL 仅暂停自动，SDEX/SDPH 保持。',
    'SEED-PNK366082-20260821',
    NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM wm_ads_operation_record WHERE external_reference = 'SEED-PNK366082-20260821'
);

SET @pnk366082_op_id = (
    SELECT id FROM wm_ads_operation_record WHERE external_reference = 'SEED-PNK366082-20260821'
);

INSERT INTO wm_ads_operation_reminder (operation_id, due_date, label, status, created_at, updated_at)
SELECT @pnk366082_op_id, '2026-08-24', '3 天复盘', 'PENDING', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM wm_ads_operation_reminder WHERE operation_id = @pnk366082_op_id AND label = '3 天复盘'
);

INSERT INTO wm_ads_operation_reminder (operation_id, due_date, label, status, created_at, updated_at)
SELECT @pnk366082_op_id, '2026-08-28', '7 天复盘', 'PENDING', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM wm_ads_operation_reminder WHERE operation_id = @pnk366082_op_id AND label = '7 天复盘'
);

INSERT INTO wm_ads_operation_reminder (operation_id, due_date, label, status, created_at, updated_at)
SELECT @pnk366082_op_id, '2026-09-04', '14 天复盘', 'PENDING', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM wm_ads_operation_reminder WHERE operation_id = @pnk366082_op_id AND label = '14 天复盘'
);

-- ============ PNK366083（T0 = 2026-08-22）============
INSERT INTO wm_ads_operation_record (
    target_type, target_key, parent_sku, target_name,
    action_type, source_type, status, occurred_at,
    title, before_value, after_value, note, external_reference,
    created_at, updated_at
)
SELECT
    'PARENT_SKU', 'PNK366083', 'PNK366083', 'PNK366083 女式背心短裤连体休闲夏日套装',
    'AD_OPTIMIZE', 'MANUAL', 'EXECUTED', '2026-08-22 12:00:00',
    'PNK366083 广告优化（T0 调整）',
    '修改前近60天整体：展示 917,469、点击 3,519、花费 $969.68、归因销售 $3,825.73、订单 188、ROAS 3.95、CPC $0.28、CVR 5.34%。主自动活动 ROAS 3.99（较近口径 2.98 下降）；混合 PNKDEAL 自动 ROAS 2.72 且重复竞争。25 个 Item 全开、无系统化否定词。',
    '主自动活动 PNK366083-ZDGG-20260622：最终 8 enabled/17 disabled，4 个 Bid $0.30、4 个 $0.25；Buy Box +10%、其余广告位 0%；新增 8 个 Negative Phrase + 5 个 Negative Exact。混合 PNKDEAL 保持 Paused。新建 SDEX 精准活动 PNK366083-SDEX-20260822：Manual Exact Fixed、4 个主力 Item、7 个成交词、Search In-grid +10%、CPC $0.30（待下次 CSV 复核）。',
    'T0 调整（2026-08-22）：
1) 主自动活动 PNK366083-ZDGG-20260622：保留 8 个 enabled（Black Rectangle XL / Pink Purple XL / Leopard Khaki L / Black XL / Leopard Khaki XL 等核心与新增 2XL），关闭 17 个（断货的 Black L、库存紧张的 Black Rectangle L、0 单的 Pink Purple L 等）；4 个 Bid $0.30、4 个 $0.25；Buy Box +10%（修改前 ROAS 7.70 最高效位），Search In-grid/Homepage/Stock Up 全 0%；新增 8 个 Negative Phrase（date night/maternity/strapless 等）+ 5 个 Negative Exact（拦截 date night rompers 等最大浪费词）。
2) 混合活动 PNKDEAL-ZDGG-20260623 保持 Paused，避免同 Item 重复竞价抬 CPC。
3) 新建精准活动 PNK366083-SDEX-20260822（Manual Exact Fixed）：投放 4 个主力 Item（Black Rectangle XL 等），7 个成交 Exact 词（rompers for women 等），Search In-grid +10%、Buy Box 0%、CPC $0.30；不添加品牌长尾词 pinksir womens romper sleeveless。
复盘检查点：3 天(2026-08-25) 确认 SDEX 审核/展示；7 天(2026-08-29) 第 7 天转化复盘；14 天(2026-09-05) 稳定性与库存复盘。
判断线：新增 2XL 累计 12-15 点击 0 单 Bid 降至 $0.20；单 Item 花费 $6 或 0 单暂停；可售≤5 件暂停；Buy Box 7 天 ROAS≥6 维持 +10%、<4 恢复 0%；SDEX 单 Exact 词 15 点击 0 单暂停、≥2 单且 ROAS≥5 提至 $0.33-0.35。',
    'SEED-PNK366083-20260822',
    NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM wm_ads_operation_record WHERE external_reference = 'SEED-PNK366083-20260822'
);

SET @pnk366083_op_id = (
    SELECT id FROM wm_ads_operation_record WHERE external_reference = 'SEED-PNK366083-20260822'
);

INSERT INTO wm_ads_operation_reminder (operation_id, due_date, label, status, created_at, updated_at)
SELECT @pnk366083_op_id, '2026-08-25', '3 天复盘', 'PENDING', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM wm_ads_operation_reminder WHERE operation_id = @pnk366083_op_id AND label = '3 天复盘'
);

INSERT INTO wm_ads_operation_reminder (operation_id, due_date, label, status, created_at, updated_at)
SELECT @pnk366083_op_id, '2026-08-29', '7 天复盘', 'PENDING', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM wm_ads_operation_reminder WHERE operation_id = @pnk366083_op_id AND label = '7 天复盘'
);

INSERT INTO wm_ads_operation_reminder (operation_id, due_date, label, status, created_at, updated_at)
SELECT @pnk366083_op_id, '2026-09-05', '14 天复盘', 'PENDING', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM wm_ads_operation_reminder WHERE operation_id = @pnk366083_op_id AND label = '14 天复盘'
);

-- ============ PNK366080（T0 = 2026-08-22）============
INSERT INTO wm_ads_operation_record (
    target_type, target_key, parent_sku, target_name,
    action_type, source_type, status, occurred_at,
    title, before_value, after_value, note, external_reference,
    created_at, updated_at
)
SELECT
    'PARENT_SKU', 'PNK366080', 'PNK366080', 'PNK366080 女式珊瑚香蕉叶阔腿裤两件套度假休闲套装',
    'AD_OPTIMIZE', 'MANUAL', 'EXECUTED', '2026-08-22 10:00:00',
    'PNK366080 广告优化（T0 调整）',
    '修改前近60天整体：展示 528,391、点击 2,238、花费 $541.82、归因销售 $2,440.83、订单 112、ROAS 4.50、CPC $0.24。几乎全部订单来自自动活动（ROAS 4.58）；SDEX 精准长期暂停/低投放（102 展示 0 单）、SDPH Phrase 与 SDGF 均 0 单。',
    '自动活动 PNK366080-ZDGG-20260604：最终 7 enabled/13 disabled，Bid 统一 $0.25，广告位全 0%，7 个 Negative Phrase + 4 个 Negative Exact。精准活动 PNK366080-SDEX-20260605：重构恢复，5 enabled Item（聚焦 Black Banana Leaf XL 等主力）、29 个 Normalized Exact 词全部 $0.35、广告位 0%。',
    'T0 调整（2026-08-21 至 22）：
1) 自动活动 PNK366080-ZDGG-20260604：保留 7 个 enabled（Black Banana Leaf XL 主力 ROAS 4.94、Black Palm Leaf XL、Black White Plaid 系列等），关闭 13 个，Bid 统一 $0.25；Homepage/Stock Up/Search In-grid/Buy Box 全 0%（无足够加价证据）；新增 7 个 Negative Phrase（linen/skirts/cheetah/embroider 等）+ 4 个 Negative Exact（2 piece woman outfit 等已验证 0 单完整查询）。
2) 精准活动 PNK366080-SDEX-20260605 重构恢复：精简为 5 个 enabled Item（Black Banana Leaf XL 等主力 + Black White Plaid 主力尺码），关闭 7 个（0 库存 Black Banana Leaf L、动销弱的 Blue Narcissus 系列等）；启用 29 个 Normalized Exact 词（承接自动活动成交词），全部 $0.35，Search In-grid/Buy Box 0%（先验证词与 Item）；Raw 与 Normalized 分栏记录，以后台 Normalized 为执行依据。
复盘检查点：3 天(2026-08-25) 确认两 Campaign 均 Live、Item/词审核正常；7 天(2026-08-29) 第 7 天转化复盘；14 天(2026-09-05) 14 天稳定性复盘。
判断线：自动单 Item 15 点击 0 单 Bid 降至 $0.20-0.22、$10 花费 0 单暂停；Black White Plaid XL 7 天 ROAS<3.5 降至 $0.20；Black Banana Leaf XL 覆盖<30 天停止放量；SDEX 单 Exact 词 15 点击 0 单暂停、≥2 单且 ROAS≥5 提至 $0.40；单 Item 库存<15 件或覆盖<30 天暂停。',
    'SEED-PNK366080-20260822',
    NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM wm_ads_operation_record WHERE external_reference = 'SEED-PNK366080-20260822'
);

SET @pnk366080_op_id = (
    SELECT id FROM wm_ads_operation_record WHERE external_reference = 'SEED-PNK366080-20260822'
);

INSERT INTO wm_ads_operation_reminder (operation_id, due_date, label, status, created_at, updated_at)
SELECT @pnk366080_op_id, '2026-08-25', '3 天复盘', 'PENDING', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM wm_ads_operation_reminder WHERE operation_id = @pnk366080_op_id AND label = '3 天复盘'
);

INSERT INTO wm_ads_operation_reminder (operation_id, due_date, label, status, created_at, updated_at)
SELECT @pnk366080_op_id, '2026-08-29', '7 天复盘', 'PENDING', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM wm_ads_operation_reminder WHERE operation_id = @pnk366080_op_id AND label = '7 天复盘'
);

INSERT INTO wm_ads_operation_reminder (operation_id, due_date, label, status, created_at, updated_at)
SELECT @pnk366080_op_id, '2026-09-05', '14 天复盘', 'PENDING', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (
    SELECT 1 FROM wm_ads_operation_reminder WHERE operation_id = @pnk366080_op_id AND label = '14 天复盘'
);
