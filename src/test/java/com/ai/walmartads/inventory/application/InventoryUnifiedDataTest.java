package com.ai.walmartads.inventory.application;

import com.ai.walmartads.inventory.repository.ForecastFactorRepository;
import com.ai.walmartads.inventory.repository.ParentDailySalesOverrideRepository;
import com.ai.walmartads.returns.application.ReturnAnalyticsService;
import com.ai.walmartads.supplychain.CurrentProductionOrder;
import com.ai.walmartads.supplychain.ProductionOrderSource;
import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class InventoryUnifiedDataTest {
    @AfterEach void clear() { WorkspaceContext.clear(); }

    @Test
    void productOnlySkuCombinesSourcesAndUnmatchedRemainsOutsideTotals() {
        WorkspaceContext.set(new WorkspacePrincipal(7, "PINKSIR", "ACTIVE", "7", "PINKSIR", "PNK", true, 1));
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        LocalDateTime snapshot = LocalDateTime.of(2026, 11, 6, 10, 0);
        when(jdbc.queryForMap(anyString(), any(SqlParameterSource.class))).thenReturn(Map.of());
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Integer.class))).thenReturn(0);
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(LocalDateTime.class)))
                .thenAnswer(call -> ((String) call.getArgument(0)).contains("wm_ads_lx_domestic_inventory") ? snapshot : null);
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.contains("row_count")) return List.of(Map.of("snapshot_at", snapshot, "row_count", 2));
            if (sql.contains("select sku, max(parent_sku)")) return List.of(
                    Map.of("sku", "PNK-A-WFS", "parent_sku", "PNK", "color", "Black", "size", "1X"),
                    Map.of("sku", "PNK-B-WFS02", "parent_sku", "PNK", "color", "Black", "size", "2X"));
            if (sql.contains("from wm_ads_lx_domestic_inventory")) return List.of(Map.of("sku", "PNK-B-WFS", "available_units", 7));
            if (sql.contains("inventory.daily_units_sold")) return List.of(
                    Map.of("sku", "PNK-A-WFS", "available_units", 5), Map.of("sku", "UNKNOWN", "available_units", 8));
            if (sql.contains("sum(orders.quantity)")) return List.of();
            throw new AssertionError("Unexpected SQL: " + sql);
        });
        ProductionOrderSource production = mock(ProductionOrderSource.class);
        when(production.currentOrders()).thenReturn(List.of(new CurrentProductionOrder("PO-1", "PNK", "PNK-B-WFS",
                null, null, BigDecimal.TEN, BigDecimal.valueOf(3), null, "SUPPLY_CHAIN")));
        TransitInventoryService transit = mock(TransitInventoryService.class);
        when(transit.load()).thenReturn(new TransitInventoryService.TransitData(
                Map.of("PNK-B-WFS", 4d, "UNKNOWN-TRANSIT", 6d), List.of(), List.of(), List.of()));
        InventoryDataService service = new InventoryDataService(jdbc, production, mock(ForecastFactorRepository.class),
                mock(ParentDailySalesOverrideRepository.class), transit, mock(ReplenishmentPlanCache.class), mock(ReturnAnalyticsService.class));

        var payload = service.loadWindow(30, 60, null, null, 0, 12, "", "all", "parentSku", "asc");
        assertThat(payload.styles()).singleElement().satisfies(style -> {
            assertThat(style.skuCount()).isEqualTo(2);
            assertThat(style.totalAvailable()).isEqualTo(5);
            assertThat(style.domesticUnits()).isEqualTo(7);
            assertThat(style.totalTransit()).isEqualTo(4);
            assertThat(style.productionUnits()).isEqualTo(3);
            assertThat(style.totalStock()).isEqualTo(19);
        });
        assertThat(payload.unmatchedStock()).extracting(InventoryDataService.UnmatchedStock::sku)
                .containsExactlyInAnyOrder("UNKNOWN", "UNKNOWN-TRANSIT");
        assertThat(service.loadStyle(30, 60, null, null, "PNK").rows())
                .filteredOn(row -> row.sku().equals("PNK-B-WFS02"))
                .singleElement().satisfies(row -> {
                    assertThat(row.availableUnits()).isZero();
                    assertThat(row.domesticUnits()).isEqualTo(7);
                    assertThat(row.risk()).isEqualTo("OUT_OF_STOCK");
                });
        var coverage = service.loadCoverage(30, 60);
        assertThat(coverage.skus()).hasSize(2);
        assertThat(coverage.styles()).singleElement().satisfies(style -> {
            assertThat(style.parentSku()).isEqualTo("PNK");
            assertThat(style.projectedDays()).isEqualTo(payload.styles().getFirst().projectedDays());
            assertThat(style.risk()).isEqualTo(payload.styles().getFirst().systemRisk());
        });
    }
}
