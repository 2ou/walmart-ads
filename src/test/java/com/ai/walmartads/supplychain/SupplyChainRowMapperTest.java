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

    @Test
    void mapsProductionOrderMasterWithoutRequiringSku() {
        List<SupplyChainOrder> orders = mapper.orders(List.of(Map.ofEntries(
                Map.entry("id", 88),
                Map.entry("orderNo", "PO-88"),
                Map.entry("styleNumber", "PNK0088"),
                Map.entry("orderStatus", "2"),
                Map.entry("orderQuantitySum", 120),
                Map.entry("cuttingNumber", 110),
                Map.entry("sendNumber", 40),
                Map.entry("warehouseNumber", 35),
                Map.entry("currentReceipts", 30),
                Map.entry("unCurrentReceipts", 90),
                Map.entry("placeOrderTime", "2026-10-17 08:30:00")
        )));

        assertThat(orders).singleElement().satisfies(row -> {
            assertThat(row.orderNo()).isEqualTo("PO-88");
            assertThat(row.styleNo()).isEqualTo("PNK0088");
            assertThat(row.orderQty()).isEqualByComparingTo("120");
            assertThat(row.unfinishedQty()).isEqualByComparingTo("90");
            assertThat(row.placedAt()).isNotNull();
        });
    }

    @Test
    void mapsReadOnlyLogisticsRegisterFields() {
        List<SupplyChainLogisticsRegister> logistics = mapper.logisticsRegisters(List.of(Map.ofEntries(
                Map.entry("id", 9),
                Map.entry("fbaNumber", "FBA-9"),
                Map.entry("accountNumber", "PINKSIR"),
                Map.entry("freightForwardingName", "测试货代"),
                Map.entry("logisticsChannelsTypeName", "海运普船"),
                Map.entry("cases", 8),
                Map.entry("quality", 320),
                Map.entry("shippingWeight", "156.5"),
                Map.entry("chargedWeight", "160"),
                Map.entry("transportationDate", "2026-10-18"),
                Map.entry("signingDate", "2026-11-20"),
                Map.entry("shipmentStatus", "已签收")
        )));

        assertThat(logistics).singleElement().satisfies(row -> {
            assertThat(row.fbaNumber()).isEqualTo("FBA-9");
            assertThat(row.casesQty()).isEqualByComparingTo("8");
            assertThat(row.itemQty()).isEqualByComparingTo("320");
            assertThat(row.chargedWeight()).isEqualByComparingTo("160");
            assertThat(row.signingDate()).hasToString("2026-11-20");
        });
    }
}
