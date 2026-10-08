-- V58 回填 wm_ads_lx_inventory_snapshot 中 parent_sku 缺失的行。
--
-- 背景：领星在线商品接口自 2026-09-23 起额外返回了一批 SKU 的库存行，
-- 写入侧（WalmartListingSyncService#writeInventorySnapshot）匹配不到产品库记录时
-- 会把 parent_sku 写为 NULL，导致这些"野 SKU"混进按款聚合的口径。
--
-- 处理：这些行的 SKU 前缀本身就是款号（如 PNK369006-... / PNK366076-...），
-- 若反解出的款号存在于有效产品库（current_version = 1）则回填 parent_sku。
-- 本脚本幂等、可重复执行。
UPDATE wm_ads_lx_inventory_snapshot inventory
   SET parent_sku = SUBSTRING_INDEX(inventory.sku, '-', 1)
 WHERE (inventory.parent_sku IS NULL OR inventory.parent_sku = '')
   AND SUBSTRING_INDEX(inventory.sku, '-', 1) IN (
           SELECT product.parent_sku
             FROM wm_ads_product_record product
            WHERE product.current_version = 1
       );
