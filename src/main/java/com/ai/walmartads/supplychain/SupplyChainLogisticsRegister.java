package com.ai.walmartads.supplychain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 供应链“物流信息登记”列表的只读标准化行。 */
public record SupplyChainLogisticsRegister(
        String sourceKey,
        String sourceId,
        String fbaNumber,
        String accountNumber,
        String deliveryWarehouseName,
        String freightForwardingName,
        String destination,
        String channelName,
        String receivingWarehouse,
        String amazonWarehouse,
        String batchNumber,
        String takeGoodsNo,
        String logisticsNo,
        BigDecimal casesQty,
        BigDecimal itemQty,
        BigDecimal shippingWeight,
        BigDecimal chargedWeight,
        BigDecimal finalOffer,
        BigDecimal logisticsAmount,
        LocalDate shipDate,
        LocalDateTime assignedAt,
        LocalDate transportationDate,
        LocalDate signingDate,
        String shipmentStatus,
        String flowStatus,
        String requestStatus,
        String exception,
        String rawPayload
) {
}
