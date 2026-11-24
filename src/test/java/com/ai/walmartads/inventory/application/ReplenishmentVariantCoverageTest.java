package com.ai.walmartads.inventory.application;

import com.ai.walmartads.supplychain.ProductionOrderSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ReplenishmentVariantCoverageTest {
    @Test
    void productVariantsWithoutWfsAreIncludedAndNumericSuffixIsMatched() {
        var jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class))).thenAnswer(call -> {
            String sql = call.getArgument(0);
            if (sql.contains("wm_ads_product_record")) return List.of(
                    Map.of("sku", "PNK-A-WFS02", "parent_sku", "PNK", "size", "1X", "color", "Black", "cutting_yield", 30),
                    Map.of("sku", "PNK-B-WFS", "parent_sku", "PNK", "size", "2X", "color", "Black"));
            return List.of(Map.of("sku", "PNK-A-WFS", "available_units", 5));
        });
        var service = new ReplenishmentPlanningService(jdbc, mock(StatsForecastClient.class),
                mock(ProductionOrderSource.class), mock(ParentLifecycleService.class), mock(TransitInventoryService.class));
        var rows = service.loadVariants(LocalDateTime.of(2026, 11, 6, 10, 0));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).wfsUnits()).isEqualTo(5);
        assertThat(rows.get(1).wfsUnits()).isZero();
        assertThat(rows.get(1).cuttingYield()).isEqualTo(30);
    }
}
