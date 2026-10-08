package com.ai.walmartads.importing.domain;

public enum ImportFileType {
    ORDER,
    AD_REPORT,
    AD_AUTO,
    AD_MANUAL,
    INVENTORY,
    /** 沃尔玛PNK周报（WFS库存快照）。扫描链路已于 2026-09-23 删除；枚举值保留仅为读回历史 import_file 行。 */
    WEEKLY_INVENTORY,
    PRODUCT,
    HISTORY,
    SKU_MAPPING,
    RETURN_ORDER,
    DEMAND_FORECAST,
    WALMART_LISTING,
    PRODUCTION_ORDER,
    SHIPMENT,
    SHIPMENT_LOGISTICS,
    DOMESTIC_INVENTORY,
    CAMPAIGN_SNAPSHOT,
    /** 领星 MCP 拉取的销量/订单日粒度数据（非文件来源，替代 PO_Data 订单明细的聚合口径）。 */
    LINGXING_ORDER;

    /**
     * 该类型是否为「整类型替换」口径：源目录只放一份文件，每次扫描以最新导入为准。
     *
     * <p>这类类型在源目录为空时，重新扫描导入会把已有数据<b>整体置空</b>（源即真理），
     * 因为「源里没有」本身就是一次完整的信息。反之：
     * <ul>
     *   <li>{@link #DOMESTIC_INVENTORY} 是快照时间序列，清掉会毁掉库存趋势；</li>
     *   <li>{@link #PRODUCT} 按 SKU 覆盖、产品库不该因删一个上传文件而消失；</li>
     *   <li>{@link #RETURN_ORDER} 按报表月替换，某月缺文件不等于该月该清。</li>
     * </ul>
     */
    public boolean fullReplace() {
        return this == PRODUCTION_ORDER || this == SHIPMENT || this == SHIPMENT_LOGISTICS;
    }
}
