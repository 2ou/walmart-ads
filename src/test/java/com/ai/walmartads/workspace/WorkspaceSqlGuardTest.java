package com.ai.walmartads.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WorkspaceSqlGuardTest {

    private final WorkspaceSqlGuard guard = new WorkspaceSqlGuard();

    @AfterEach
    void clear() {
        WorkspaceContext.clear();
    }

    @Test
    void scopesEveryReadTableOccurrenceIncludingSubqueries() {
        bind(27L);
        String rewritten = guard.rewrite("""
                with sold as (select sku from wm_ads_order_record where order_date >= ?)
                select p.sku from wm_ads_product_record p
                join sold s on s.sku=p.sku
                left join wm_ads_lx_ad_report ads on ads.sku=p.sku
                """);

        assertThat(rewritten).contains("from (select * from wm_ads_order_record where workspace_id = 27) as wm_ads_order_record")
                .contains("from (select * from wm_ads_product_record where workspace_id = 27) p")
                .contains("join (select * from wm_ads_lx_ad_report where workspace_id = 27) ads");
    }

    @Test
    void addsRequiredAliasWhenReadTableHasNoExplicitAlias() {
        bind(1L);

        String rewritten = guard.rewrite("""
                select distinct upper(parent_sku) as parent_sku
                  from wm_ads_product_record
                 where current_version = true
                """);

        assertThat(rewritten).contains(
                "from (select * from wm_ads_product_record where workspace_id = 1) as wm_ads_product_record")
                .contains("where current_version = true");
    }

    @Test
    void scopesDashboardSummaryTables() {
        bind(11L);

        assertThat(guard.rewrite("select max(report_date) from wm_ads_dashboard_ad_daily"))
                .contains("from (select * from wm_ads_dashboard_ad_daily where workspace_id = 11) as wm_ads_dashboard_ad_daily");
    }

    @Test
    void addsWorkspaceLiteralWithoutChangingPreparedParameterCount() {
        bind(8L);
        String rewritten = guard.rewrite("""
                insert into wm_ads_price_change (sku, old_price, new_price) values (?, ?, ?)
                """);

        assertThat(rewritten).contains("(workspace_id, sku, old_price, new_price) values (8, ?, ?, ?)");
    }

    @Test
    void scopesUpdateTargetAndJoinedTenantTable() {
        bind(3L);
        String rewritten = guard.rewrite("""
                update wm_ads_order_record orders
                join wm_ads_sku_mapping mapping on mapping.sku=orders.sku
                set orders.upc=mapping.upc
                where orders.upc is null
                """);

        assertThat(rewritten).contains("where orders.workspace_id = 3 and")
                .contains("join (select * from wm_ads_sku_mapping where workspace_id = 3) mapping");
    }

    @Test
    void scopesDelete() {
        bind(9L);
        assertThat(guard.rewrite("delete from wm_ads_lx_ad_slice where report_date=?"))
                .contains("where wm_ads_lx_ad_slice.workspace_id = 9 and");
    }

    @Test
    void leavesGlobalAndContextFreeSqlUntouched() {
        String sql = "select * from wm_ads_order_record";
        assertThat(guard.rewrite(sql)).isEqualTo(sql);
        bind(2L);
        assertThat(guard.rewrite("select * from wm_ads_workspace")).isEqualTo("select * from wm_ads_workspace");
    }

    private static void bind(long id) {
        WorkspaceContext.set(new WorkspacePrincipal(id, "tenant", "ACTIVE", "store", "Store", null, false, 1L));
    }
}
