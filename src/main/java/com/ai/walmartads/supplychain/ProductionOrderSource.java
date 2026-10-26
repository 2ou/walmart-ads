package com.ai.walmartads.supplychain;

import com.ai.walmartads.workspace.WorkspaceContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 成功同步后读取当前工作区供应链快照；首次成功前不展示旧导入数据。 */
@Service
public class ProductionOrderSource {

    private final NamedParameterJdbcTemplate jdbc;
    private final SupplyChainStore store;

    public ProductionOrderSource(
            NamedParameterJdbcTemplate jdbc,
            SupplyChainStore store
    ) {
        this.jdbc = jdbc;
        this.store = store;
    }

    @Transactional(readOnly = true)
    public List<CurrentProductionOrder> currentOrders() {
        if (store.hasSuccessfulSnapshot()) {
            long workspaceId = WorkspaceContext.requireId();
            return jdbc.query("""
                    select order_no, style_no, sku, order_date, cutting_qty,
                           production_qty, unfinished_qty, factory_return_date
                      from wm_ads_supply_production_order
                     where workspace_id=:workspaceId and active = 1
                     order by order_no, sku
                    """, new MapSqlParameterSource("workspaceId", workspaceId), (rs, rowNum) -> new CurrentProductionOrder(
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
        return List.of();
    }

    public boolean usingSupplyChain() {
        return store.hasSuccessfulSnapshot();
    }
}
