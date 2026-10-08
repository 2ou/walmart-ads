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
        String source
) {
}
