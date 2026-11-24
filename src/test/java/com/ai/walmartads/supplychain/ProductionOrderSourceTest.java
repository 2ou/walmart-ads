package com.ai.walmartads.supplychain;

import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionOrderSourceTest {
    @AfterEach
    void clearWorkspace() { WorkspaceContext.clear(); }

    @Test
    void matchesPlannedDeliverySnapshotForTheCurrentWorkspaceOnly() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:production_source;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var sql = new JdbcTemplate(dataSource);
        sql.execute("create table wm_ads_import_file(workspace_id bigint, file_type varchar(50), active int, status varchar(20), created_at timestamp)");
        sql.execute("create table wm_ads_product_record(workspace_id bigint, current_version boolean, sku varchar(100))");
        sql.execute("create table wm_ads_supply_production_order(workspace_id bigint, active int, source_key varchar(64), order_no varchar(40), style_no varchar(40), sku varchar(100), order_date date, cutting_qty decimal, production_qty decimal, unfinished_qty decimal, factory_return_date varchar(50))");
        sql.execute("create table wm_ads_supply_delivery_detail(workspace_id bigint, active int, order_no varchar(40), sku varchar(100), plan_flag varchar(10), ship_qty decimal)");
        sql.update("insert into wm_ads_product_record values (1, true, 'PNK-A-WFS02')");
        sql.update("insert into wm_ads_supply_production_order values (1,1,'a','PO-1','PNK','PNK-A-WFS',null,100,100,2,null)");
        sql.update("insert into wm_ads_supply_delivery_detail values (1,1,'PO-1','PNK-A-WFS02','1',40)");
        sql.update("insert into wm_ads_supply_delivery_detail values (1,1,'PO-1','PNK-A-WFS02','0',20)");
        sql.update("insert into wm_ads_supply_delivery_detail values (2,1,'PO-1','PNK-A-WFS02','1',50)");
        sql.update("insert into wm_ads_supply_delivery_detail values (1,0,'PO-1','PNK-A-WFS02','1',50)");
        var store = mock(SupplyChainStore.class);
        when(store.lastSuccessAt()).thenReturn(LocalDateTime.of(2026, 11, 3, 8, 0));
        WorkspaceContext.set(new WorkspacePrincipal(1L, "PINKSIR", "ACTIVE", "1", "PINKSIR", "PNK", false, 1L));

        var rows = new ProductionOrderSource(new NamedParameterJdbcTemplate(dataSource), store).currentOrders();

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.sku()).isEqualTo("PNK-A-WFS02");
            assertThat(row.plannedShipQty()).isEqualByComparingTo("40");
            assertThat(row.unplannedShipQty()).isEqualByComparingTo("20");
            assertThat(row.wipQty()).isEqualByComparingTo("60");
        });
    }
}
