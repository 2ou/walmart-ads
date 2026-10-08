package com.ai.walmartads.importing.parser;

import java.math.BigDecimal;

/**
 * 「沃尔玛商品」在线列表的一行解析结果（非 JPA 实体，仅用于传递数据）。
 * 字段直接回写到 {@code wm_ads_product_record} 对应列（按 SKU 全变体匹配）。
 */
public record WalmartListingRow(
        /** 商品 SKU（匹配键，如 PNK366067-C-Purple Gray-M-WFS） */
        String sku,
        /** 父 SKU（品名列，仅参考，不落库） */
        String parentSku,
        /** 商品ID（可空） */
        String productId,
        /** UPC（可空） */
        String upc,
        /** GTIN（可空） */
        String gtin,
        /** 价格（USD，可空） */
        BigDecimal price,
        /** 划线价（USD，可空） */
        BigDecimal strikethroughPrice,
        /** 上架状态（可空，如 PUBLISHED/UNPUBLISHED） */
        String listingStatus,
        /** 店铺（可空，如 PINKSIR） */
        String store
) {
}
