package com.ai.walmartads.supplychain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 供应链生产发货单的 SKU 明细。 */
public record SupplyChainDeliveryDetail(
        String sourceKey,
        String sourceId,
        String deliveryNo,
        String orderNo,
        String styleNo,
        String sku,
        String cuttingNo,
        String model,
        BigDecimal orderQty,
        BigDecimal shipQty,
        BigDecimal warehouseReceiveQty,
        BigDecimal qcReceiveQty,
        BigDecimal qcPassQty,
        BigDecimal reworkQty,
        String deliveryStatus,
        String planFlag,
        Boolean logisticsComplete,
        LocalDateTime assignedAt,
        LocalDateTime sourceCreatedAt,
        String factoryName,
        String warehouseName,
        String rawPayload
) {
}
