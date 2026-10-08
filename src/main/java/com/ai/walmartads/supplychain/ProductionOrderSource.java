package com.ai.walmartads.supplychain;

import com.ai.walmartads.importing.repository.ProductionOrderRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/** 成功同步后读取供应链快照；首次成功前无缝回退旧 Excel。 */
@Service
public class ProductionOrderSource {

    private final NamedParameterJdbcTemplate jdbc;
    private final ProductionOrderRepository legacyRepository;
    private final SupplyChainStore store;

    public ProductionOrderSource(
            NamedParameterJdbcTemplate jdbc,
            ProductionOrderRepository legacyRepository,
            SupplyChainStore store
    ) {
        this.jdbc = jdbc;
        this.legacyRepository = legacyRepository;
        this.store = store;
    }

    @Transactional(readOnly = true)
    public List<CurrentProductionOrder> currentOrders() {
        if (store.hasSuccessfulSnapshot()) {
            return jdbc.query("""
                    select order_no, style_no, sku, order_date, cutting_qty,
                           production_qty, unfinished_qty, factory_return_date
                      from wm_ads_supply_production_order
                     where active = 1
                     order by order_no, sku
                    """, new MapSqlParameterSource(), (rs, rowNum) -> new CurrentProductionOrder(
                    rs.getString("order_no"),
                    rs.getString("style_no"),
                    rs.getString("sku"),
                    rs.getObject("order_date", java.time.LocalDate.class),
                    rs.getBigDecimal("cutting_qty"),
                    rs.getBigDecimal("production_qty"),
                    rs.getBigDecimal("unfinished_qty"),
                    rs.getString("factory_return_date"),
                    "SUPPLY_CHAIN"
            ));
        }
        return legacyRepository.findCurrentVersions().stream()
                .map(row -> new CurrentProductionOrder(
                        row.getOrderNo(),
                        row.getStyleNo(),
                        row.getSku(),
                        row.getOrderDate(),
                        row.getCuttingQty(),
                        value(row.getProductionQty()),
                        value(row.getProductionQty()),
                        row.getFactoryReturnDate(),
                        "LEGACY_IMPORT"
                ))
                .toList();
    }

    public boolean usingSupplyChain() {
        return store.hasSuccessfulSnapshot();
    }

    private static BigDecimal value(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
