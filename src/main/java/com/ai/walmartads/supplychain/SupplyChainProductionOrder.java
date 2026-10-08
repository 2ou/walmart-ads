package com.ai.walmartads.supplychain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 供应链生产中订单的标准化行。 */
public record SupplyChainProductionOrder(
        String sourceKey,
        String sourceId,
        String orderNo,
        String styleNo,
        String sku,
        LocalDate orderDate,
        String model,
        BigDecimal openQty,
        BigDecimal cuttingQty,
        BigDecimal productionQty,
        BigDecimal shipQty,
        BigDecimal warehouseReceiveQty,
        BigDecimal qcPassQty,
        BigDecimal reworkQty,
        BigDecimal unfinishedQty,
        String factoryReturnDate,
        String storeName,
        String orderType,
        String factoryName,
        String warehouseName,
        String rawPayload
) {
}
