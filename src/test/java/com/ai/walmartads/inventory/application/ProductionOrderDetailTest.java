package com.ai.walmartads.inventory.application;

import com.ai.walmartads.inventory.repository.ForecastFactorRepository;
import com.ai.walmartads.inventory.repository.ParentDailySalesOverrideRepository;
import com.ai.walmartads.returns.application.ReturnAnalyticsService;
import com.ai.walmartads.supplychain.CurrentProductionOrder;
import com.ai.walmartads.supplychain.ProductionOrderSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionOrderDetailTest {
    @Test
    void countsOnlyProductSkusEvenWithoutAWfsSnapshot() {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        ProductionOrderSource source = mock(ProductionOrderSource.class);
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), org.mockito.ArgumentMatchers.eq(String.class)))
                .thenReturn(List.of("PNK100-M"));
        when(source.currentOrders()).thenReturn(List.of(
                row("PNK100-M", "100", "7"), row("PNK200-L", "80", "12")));
        InventoryDataService service = new InventoryDataService(jdbc, source,
                mock(ForecastFactorRepository.class), mock(ParentDailySalesOverrideRepository.class),
                mock(TransitInventoryService.class), mock(ReplenishmentPlanCache.class), mock(ReturnAnalyticsService.class));

        List<InventoryDataService.ProductionOrderView> result = service.productionOrders();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).productionQty()).isEqualTo(100);
        assertThat(result.get(0).unfinishedQty()).isEqualTo(7);
        assertThat(result.get(0).inventoryQty()).isEqualTo(7);
        assertThat(result.get(0).includedInInventory()).isTrue();
        assertThat(result.get(1).unfinishedQty()).isEqualTo(12);
        assertThat(result.get(1).inventoryQty()).isZero();
        assertThat(result.get(1).includedInInventory()).isFalse();
    }

    private static CurrentProductionOrder row(String sku, String produced, String unfinished) {
        return new CurrentProductionOrder("PO-1", "PNK100", sku, null, null,
                new BigDecimal(produced), new BigDecimal(unfinished), "2026-11-10", "SUPPLY_CHAIN");
    }
}
