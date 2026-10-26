package com.ai.walmartads.supplychain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 供应链生产订单主档的标准化行。 */
public record SupplyChainOrder(
        String sourceKey,
        String sourceId,
        String orderNo,
        String orderStatus,
        String styleNo,
        String orderType,
        String factoryName,
        String warehouseName,
        String storeName,
        BigDecimal orderQty,
        BigDecimal cuttingQty,
        BigDecimal shipQty,
        BigDecimal warehouseReceiveQty,
        BigDecimal qcPassQty,
        BigDecimal unfinishedQty,
        LocalDateTime placedAt,
        LocalDateTime assignedAt,
        String factoryReturnDate,
        LocalDateTime completedAt,
        String rawPayload
) {
}
