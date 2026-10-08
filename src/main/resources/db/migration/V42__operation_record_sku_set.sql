-- V42：运营记录作用范围改为「SKU 集合」
-- 业务口径：每条运营记录必须具体到 item（SKU）；全选该款在售 SKU 即等价「整款」。
--   target_keys = 作用的 SKU 集合（JSON 数组快照）
--   target_type = PARENT_SKU（集合覆盖全款）/ SKU（部分 SKU）
--   target_key  = 整款时为款号，部分时为首个 SKU（由后端写入，前端不再手填）

ALTER TABLE wm_ads_operation_record
    ADD COLUMN target_keys JSON NULL COMMENT '作用 SKU 集合快照（JSON 数组）；覆盖全款即等价整款'
    AFTER target_key;

-- 回填历史：#11 为 PNK363055 自动广告，实际仅投放 6 个 3X 花色（非全款 18 个 SKU）
UPDATE wm_ads_operation_record
   SET target_keys = CAST(
           '["PNK363055-FP-Black White Floral-3X-WFS",'
           '"PNK363055-FP-Green Butterfly-3X-WFS",'
           '"PNK363055-FP-LD Snow Pink-3X-WFS",'
           '"PNK363055-FP-Leopard Khaki-3X-WFS",'
           '"PNK363055-FP-Pink Star-3X-WFS",'
           '"PNK363055-FP-Speckled Blue-3X-WFS"]' AS JSON),
       target_type = 'SKU',
       target_key  = 'PNK363055-FP-Black White Floral-3X-WFS'
 WHERE id = 11
   AND target_keys IS NULL;
