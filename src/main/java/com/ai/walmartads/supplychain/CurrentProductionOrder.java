package com.ai.walmartads.supplychain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 库存和补货模块共同消费的生产订单口径。 */
public record CurrentProductionOrder(
        String orderNo,
        String styleNo,
        String sku,
        LocalDate orderDate,
        BigDecimal cuttingQty,
        BigDecimal productionQty,
        BigDecimal wipQty,
        String factoryReturnDate,
        String source,
        BigDecimal plannedShipQty,
        BigDecimal unplannedShipQty,
        String reconciliationStatus
) {
    public CurrentProductionOrder(String orderNo, String styleNo, String sku, LocalDate orderDate,
                                  BigDecimal cuttingQty, BigDecimal productionQty, BigDecimal wipQty,
                                  String factoryReturnDate, String source) {
        this(orderNo, styleNo, sku, orderDate, cuttingQty, productionQty, wipQty,
                factoryReturnDate, source, null, null, "FILE_UPLOAD");
    }
}
