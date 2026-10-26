package com.ai.walmartads.importing.application;

import com.ai.walmartads.importing.domain.ImportFileType;

/**
 * 前端「导入范围」可选来源。工作簿只通过页面上传进入系统；自动数据由各连接器维护。
 *
 * <p>已退役、不在此枚举中的来源：
 * <ul>
 *   <li>{@code SKU_MAPPING}（WFS 上传模板，2026-09-28 移出）—— 数据源换成领星 MCP 在线商品，
 *       由 {@code WalmartListingSyncService} 每小时顺带写入，不再需要文件导入</li>
 *   <li>{@code HISTORY}（早期销售历史，2026-09-28 移出）</li>
 *   <li>{@code DEMAND_FORECAST}（源目录已不存在，死链路）</li>
 *   <li>{@code ORDER}（PO_Data 每日订单明细已由领星 MCP 接管，2026-09-28 移出）</li>
 * </ul>
 * 四者的 {@link ImportFileType} 枚举值保留，仅用于读回历史 {@code wm_ads_import_file} 行。
 */
public enum ImportSource {
    PRODUCT,
    RETURN_ORDER,
    PRODUCTION_ORDER,
    SHIPMENT,
    SHIPMENT_LOGISTICS,
    DOMESTIC_INVENTORY;

    /** 本来源对应的文件类型。 */
    public ImportFileType fileType() {
        return switch (this) {
            case PRODUCT -> ImportFileType.PRODUCT;
            case RETURN_ORDER -> ImportFileType.RETURN_ORDER;
            case PRODUCTION_ORDER -> ImportFileType.PRODUCTION_ORDER;
            case SHIPMENT -> ImportFileType.SHIPMENT;
            case SHIPMENT_LOGISTICS -> ImportFileType.SHIPMENT_LOGISTICS;
            case DOMESTIC_INVENTORY -> ImportFileType.DOMESTIC_INVENTORY;
        };
    }

}
