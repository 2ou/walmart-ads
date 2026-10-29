package com.ai.walmartads.supplychain;

import com.ai.walmartads.inventory.application.LingxingSourcePolicy;
import com.ai.walmartads.workspace.WorkspaceContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 自动供应链快照为主；用户上传了更新的生产订单文件时用于补货计算兜底。 */
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
        long workspaceId = WorkspaceContext.requireId();
        LocalDateTime automaticAt = store.lastSuccessAt();
        LocalDateTime uploadAt = latestUploadAt(workspaceId);
        boolean automatic = LingxingSourcePolicy.preferAutomatic(automaticAt, uploadAt);
        List<CurrentProductionOrder> rows;
        if (automatic) {
            rows = jdbc.query("""
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
        } else {
            rows = jdbc.query("""
                    select order_no, style_no, sku, order_date, cutting_qty,
                           production_qty, unfinished_qty, factory_return_date
                      from wm_ads_production_order
                     where workspace_id=:workspaceId and current_version=1
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
                    "FILE_UPLOAD"
            ));
        }
        ProductionSkuCanonicalizer canonicalizer = new ProductionSkuCanonicalizer(jdbc.queryForList("""
                select sku from wm_ads_product_record
                 where workspace_id=:workspaceId and current_version=1 and sku is not null
                """, new MapSqlParameterSource("workspaceId", workspaceId), String.class));
        return rows.stream().map(row -> new CurrentProductionOrder(
                row.orderNo(), row.styleNo(), canonicalizer.resolve(row.sku()), row.orderDate(),
                row.cuttingQty(), row.productionQty(), row.wipQty(), row.factoryReturnDate(), row.source()
        )).toList();
    }

    public boolean usingSupplyChain() {
        long workspaceId = WorkspaceContext.requireId();
        return LingxingSourcePolicy.preferAutomatic(store.lastSuccessAt(), latestUploadAt(workspaceId));
    }

    private LocalDateTime latestUploadAt(long workspaceId) {
        return jdbc.query("""
                        select max(created_at) latest_at
                          from wm_ads_import_file
                         where workspace_id=:workspaceId and file_type='PRODUCTION_ORDER'
                           and active=1 and status='IMPORTED'
                        """, new MapSqlParameterSource("workspaceId", workspaceId),
                rs -> rs.next() && rs.getTimestamp("latest_at") != null
                        ? rs.getTimestamp("latest_at").toLocalDateTime()
                        : null);
    }
}
