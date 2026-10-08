package com.ai.walmartads.product.application;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class RefundRateServiceTest {

    @Test
    void usesImportedMonthRangeAndDeduplicatesUpdatedReturnRows() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        try {
            NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(database);
            jdbc.getJdbcTemplate().execute("""
                    create table wm_ads_import_file (
                        id bigint primary key,
                        active boolean not null,
                        created_at timestamp not null
                    )
                    """);
            jdbc.getJdbcTemplate().execute("""
                    create table wm_ads_return_order (
                        id bigint primary key,
                        import_file_id bigint not null,
                        business_key varchar(64) not null,
                        parent_sku varchar(80),
                        item_id varchar(80),
                        current_status varchar(80),
                        quantity decimal(18, 4),
                        report_month date
                    )
                    """);
            jdbc.getJdbcTemplate().execute("""
                    create table wm_ads_sku_mapping (
                        item_id varchar(80),
                        parent_sku varchar(80),
                        current_version boolean
                    )
                    """);
            jdbc.getJdbcTemplate().execute("""
                    create table wm_ads_product_record (
                        sku varchar(180),
                        parent_sku varchar(80),
                        current_version boolean
                    )
                    """);
            jdbc.getJdbcTemplate().execute("""
                    create table wm_ads_order_record (
                        import_file_id bigint not null,
                        sku varchar(180),
                        parent_sku varchar(80),
                        quantity decimal(18, 4),
                        order_date date
                    )
                    """);
            // V53：订单读路径走 wm_ads_order_record_valid 视图（只保留产品库 current_version=1 的款）。
            jdbc.getJdbcTemplate().execute("""
                    create view wm_ads_order_record_valid as
                    select o.*
                      from wm_ads_order_record o
                     where o.parent_sku in (
                           select p.parent_sku
                             from wm_ads_product_record p
                            where p.current_version = true
                     )
                    """);
            jdbc.getJdbcTemplate().update("insert into wm_ads_import_file values (1, true, timestamp '2026-07-01 00:00:00')");
            jdbc.getJdbcTemplate().update("insert into wm_ads_import_file values (2, true, timestamp '2026-07-02 00:00:00')");
            jdbc.getJdbcTemplate().update("insert into wm_ads_product_record values ('SKU-1', 'PNK100', true)");
            jdbc.getJdbcTemplate().update("""
                    insert into wm_ads_return_order
                        (id, import_file_id, business_key, parent_sku, item_id, current_status, quantity, report_month)
                    values
                        (1, 1, 'same-rma-line', 'PNK100', 'SKU-1', 'RETURN_INVOICED', 2, date '2025-09-01'),
                        (2, 2, 'same-rma-line', 'PNK100', 'SKU-1', 'RETURN_INVOICED', 3, date '2026-07-01'),
                        (3, 1, 'another-rma-line', 'PNK100', 'SKU-1', 'RETURN_INVOICED', 2, date '2025-09-01')
                    """);
            jdbc.getJdbcTemplate().update("""
                    insert into wm_ads_order_record (import_file_id, sku, parent_sku, quantity, order_date)
                    values
                        (1, 'SKU-1', 'PNK100', 100, date '2025-08-31'),
                        (1, 'SKU-1', 'PNK100', 20, date '2025-09-01'),
                        (1, 'SKU-1', 'PNK100', 30, date '2026-07-31'),
                        (1, 'SKU-1', 'PNK100', 50, date '2026-08-01')
                    """);

            var rates = new RefundRateService(jdbc).ratesByParent();

            assertThat(rates.get("PNK100")).isEqualByComparingTo(new BigDecimal("0.100000"));
        } finally {
            database.shutdown();
        }
    }
}
