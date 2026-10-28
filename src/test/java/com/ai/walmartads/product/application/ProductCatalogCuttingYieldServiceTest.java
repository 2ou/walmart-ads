package com.ai.walmartads.product.application;

import com.ai.walmartads.importing.domain.ProductRecord;
import com.ai.walmartads.importing.repository.ProductRecordRepository;
import com.ai.walmartads.inventory.application.ParentLifecycleService;
import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductCatalogCuttingYieldServiceTest {

    private NamedParameterJdbcTemplate jdbc;
    private ProductRecordRepository products;
    private ProductCatalogService service;

    @BeforeEach
    void setUp() {
        WorkspaceContext.set(new WorkspacePrincipal(9L, "SHOP", "ACTIVE", "9", "Shop", "SHP", false, 1L));
        jdbc = mock(NamedParameterJdbcTemplate.class);
        products = mock(ProductRecordRepository.class);
        service = new ProductCatalogService(
                jdbc, new ObjectMapper(), new ProfitCalculator(), null, null, products,
                mock(ParentLifecycleService.class), mock(ProductDashboardService.class));
    }

    @AfterEach
    void tearDown() {
        WorkspaceContext.clear();
    }

    @Test
    void savesCuttingYieldAsDurableWorkspaceParentSetting() {
        ProductRecord first = product("PNK100-Blue-M-WFS");
        ProductRecord second = product("PNK100-Blue-L-WFS");
        when(products.findByParentSku("PNK100")).thenReturn(List.of(first, second));

        service.updateCuttingYieldByParent("PNK100", 85);

        assertThat(first.getCuttingYield()).isEqualTo(85);
        assertThat(second.getCuttingYield()).isEqualTo(85);
        verify(products).saveAll(List.of(first, second));
        verify(jdbc).update(contains("insert into wm_ads_product_manual_setting"),
                any(MapSqlParameterSource.class));
    }

    private static ProductRecord product(String sku) {
        return new ProductRecord(
                null, LocalDateTime.of(2026, 10, 10, 12, 0), sku, "PNK100",
                null, null, null, null, null, null, "[]", null, "[]",
                null, null, null, null, null, null, null, null, "{}"
        );
    }
}
