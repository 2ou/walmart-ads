package com.ai.walmartads.supplychain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SupplyChainRowMapperTest {

    private final SupplyChainRowMapper mapper = new SupplyChainRowMapper(new ObjectMapper());

    @Test
    void deliveryQuantitiesReduceFactoryWipWithoutDoubleCounting() {
        List<Map<String, Object>> rawDeliveries = List.of(
                Map.of(
                        "id", 1,
                        "deliverGoodsNo", "FH-1",
                        "orderNo", "PO-1",
                        "stockKeepingUnit", "PNK-A-BLACK-S-WFS",
                        "sendNumber", 10,
                        "harvestNumber", 8,
                        "qiQualifiedNumber", 7
                ),
                Map.of(
                        "id", 2,
                        "deliverGoodsNo", "FH-2",
                        "orderNo", "PO-1",
                        "stockKeepingUnit", "PNK-A-BLACK-S-WFS",
                        "sendNumber", 5,
                        "harvestNumber", 5,
                        "qiQualifiedNumber", 4
                )
        );
        List<SupplyChainDeliveryDetail> deliveries = mapper.deliveries(rawDeliveries);
        List<SupplyChainProductionOrder> production = mapper.productionOrders(List.of(Map.of(
                "id", 10,
                "orderNo", "PO-1",
                "styleNumber", "PNK-A",
                "stockKeepingUnit", "PNK-A-BLACK-S-WFS",
                "orderQuantity", 50,
                "cuttingNumber", 40,
                "sendNumber", 3
        )), deliveries);

        assertThat(deliveries).hasSize(2);
        assertThat(production).singleElement().satisfies(row -> {
            assertThat(row.productionQty()).isEqualByComparingTo(new BigDecimal("40"));
            assertThat(row.shipQty()).isEqualByComparingTo(new BigDecimal("15"));
            assertThat(row.warehouseReceiveQty()).isEqualByComparingTo(new BigDecimal("13"));
            assertThat(row.qcPassQty()).isEqualByComparingTo(new BigDecimal("11"));
            assertThat(row.unfinishedQty()).isEqualByComparingTo(new BigDecimal("25"));
        });
    }

    @Test
    void fallsBackToOrderQuantityWhenNothingWasCut() {
        List<SupplyChainProductionOrder> production = mapper.productionOrders(List.of(Map.of(
                "orderNo", "PO-2",
                "stockKeepingUnit", "PNK-B-WHITE-M-WFS",
                "orderQuantity", "12",
                "cuttingNumber", "0"
        )), List.of());

        assertThat(production).singleElement().satisfies(row -> {
            assertThat(row.productionQty()).isEqualByComparingTo("12");
            assertThat(row.unfinishedQty()).isEqualByComparingTo("12");
            assertThat(row.sourceKey()).hasSize(64);
        });
    }
}
