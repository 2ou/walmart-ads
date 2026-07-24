package com.ai.walmartads.sales.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class SalesDataServiceTest {

    private NamedParameterJdbcTemplate jdbc;
    private SalesDataService service;

    @BeforeEach
    void setUp() {
        EmbeddedDatabase database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        jdbc = new NamedParameterJdbcTemplate(database);
        jdbc.getJdbcTemplate().execute("""
                create table wm_ads_import_file (
                    id bigint primary key,
                    active boolean not null
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                create table wm_ads_order_record (
                    id bigint primary key,
                    import_file_id bigint not null,
                    order_date date not null,
                    order_number varchar(80) not null,
                    sku varchar(180) not null,
                    parent_sku varchar(80),
                    item_description varchar(500),
                    quantity decimal(18,4) not null,
                    item_cost decimal(18,4) not null
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                create table wm_ads_product_record (
                    id bigint primary key,
                    import_file_id bigint not null,
                    current_version boolean not null,
                    sku varchar(180) not null,
                    parent_sku varchar(80),
                    product_name varchar(500),
                    main_image_url varchar(2500),
                    color varchar(600),
                    size varchar(120)
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                create table wm_ads_inventory_snapshot (
                    id bigint primary key,
                    import_file_id bigint not null,
                    snapshot_at timestamp not null,
                    sku varchar(180) not null,
                    parent_sku varchar(80),
                    item_id varchar(80),
                    item_name varchar(500),
                    available_units decimal(18,4) not null,
                    reserved_units decimal(18,4) not null,
                    inbound_units decimal(18,4) not null
                )
                """);
        jdbc.getJdbcTemplate().update("insert into wm_ads_import_file values (1, true), (2, true), (3, true), (4, false)");

        insertOrder(1, 1, "2026-07-20", "O1", "SKU-A", "P1", 2, 10);
        insertOrder(2, 1, "2026-07-21", "O2", "SKU-A", "P1", 3, 12);
        insertOrder(3, 1, "2026-07-22", "O3", "SKU-A", "P1", 1, 11);
        insertOrder(4, 1, "2026-07-22", "O4", "SKU-B", "P1", 2, 20);
        insertOrder(5, 1, "2026-07-19", "O5", "SKU-A", "P1", 2, 9);
        insertOrder(6, 1, "2026-07-18", "O6", "SKU-C", "P2", 4, 8);
        insertOrder(7, 4, "2026-07-22", "IGNORED", "SKU-A", "P1", 100, 99);

        insertProduct(1, "SKU-A", "P1", "Blue shirt", "https://img/a.jpg", "Blue", "M");
        insertProduct(2, "SKU-B", "P1", "Red shirt", "https://img/b.jpg", "Red", "L");
        insertProduct(3, "SKU-C", "P2", "Black dress", "https://img/c.jpg", "Black", "S");
        insertProduct(4, "SKU-D", "P2", "White dress", "https://img/d.jpg", "White", "XL");

        insertInventory(1, "SKU-A", "P1", 12, 1, 4);
        insertInventory(2, "SKU-B", "P1", 0, 0, 2);
        insertInventory(3, "SKU-C", "P2", 5, 0, 0);
        insertInventory(4, "SKU-D", "P2", 10, 1, 0);
        service = new SalesDataService(jdbc);
    }

    @Test
    void buildsSalesMetricsFromOrderDateAndItemCost() {
        SalesDataService.SalesPayload payload = service.load(3);

        assertThat(payload.meta().latestDate()).isEqualTo("2026-07-22");
        assertThat(payload.meta().currentStart()).isEqualTo("2026-07-20");
        assertThat(payload.meta().priceSource()).isEqualTo("订单表 Item Cost");
        assertThat(payload.meta().inventorySnapshotAt()).isEqualTo(LocalDateTime.of(2026, 7, 22, 12, 0));
        assertThat(payload.summary().units()).isEqualTo(8);
        assertThat(payload.summary().revenue()).isEqualTo(107);
        assertThat(payload.summary().orders()).isEqualTo(4);
        assertThat(payload.summary().averageUnitPrice()).isEqualTo(13.375);
        assertThat(payload.summary().activeSkus()).isEqualTo(2);
        assertThat(payload.summary().knownSkus()).isEqualTo(4);
        assertThat(payload.summary().activeRate()).isEqualTo(0.5);
        assertThat(payload.daily()).hasSize(3);
    }

    @Test
    void groupsByParentAndKeepsVariantDimensionsAndInventoryCover() {
        SalesDataService.SalesPayload payload = service.load(3);
        SalesDataService.SkuRow skuA = payload.skuRows().stream()
                .filter(row -> row.sku().equals("SKU-A"))
                .findFirst()
                .orElseThrow();
        SalesDataService.ParentRow parent = payload.parentRows().stream()
                .filter(row -> row.parentSku().equals("P1"))
                .findFirst()
                .orElseThrow();

        assertThat(skuA.units()).isEqualTo(6);
        assertThat(skuA.revenue()).isEqualTo(67);
        assertThat(skuA.availableUnits()).isEqualTo(12);
        assertThat(skuA.reservedUnits()).isEqualTo(1);
        assertThat(skuA.transitUnits()).isEqualTo(4);
        assertThat(skuA.availableDays()).isEqualTo(6);
        assertThat(skuA.status()).isEqualTo("GROWING");
        assertThat(parent.units()).isEqualTo(8);
        assertThat(parent.availableUnits()).isEqualTo(12);
        assertThat(parent.reservedUnits()).isEqualTo(1);
        assertThat(parent.transitUnits()).isEqualTo(6);
        assertThat(parent.availableDays()).isEqualTo(4.5);
        assertThat(parent.variantCount()).isEqualTo(2);
        assertThat(parent.colorCount()).isEqualTo(2);
        assertThat(parent.sizeCount()).isEqualTo(2);
        assertThat(payload.colors()).extracting(SalesDataService.DimensionRow::label)
                .contains("Blue", "Red", "Black", "White");
        assertThat(payload.skuRows().stream()
                .filter(row -> row.sku().equals("SKU-D"))
                .findFirst()
                .orElseThrow()
                .status()).isEqualTo("NO_SALES");
    }

    private void insertOrder(
            long id,
            long fileId,
            String date,
            String order,
            String sku,
            String parent,
            double quantity,
            double price
    ) {
        jdbc.getJdbcTemplate().update("""
                insert into wm_ads_order_record (
                    id, import_file_id, order_date, order_number, sku, parent_sku,
                    item_description, quantity, item_cost
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, fileId, date, order, sku, parent, sku + " item", quantity, price);
    }

    private void insertProduct(
            long id,
            String sku,
            String parent,
            String name,
            String image,
            String color,
            String size
    ) {
        jdbc.getJdbcTemplate().update("""
                insert into wm_ads_product_record (
                    id, import_file_id, current_version, sku, parent_sku,
                    product_name, main_image_url, color, size
                ) values (?, 2, true, ?, ?, ?, ?, ?, ?)
                """, id, sku, parent, name, image, color, size);
    }

    private void insertInventory(
            long id,
            String sku,
            String parent,
            double available,
            double reserved,
            double inbound
    ) {
        jdbc.getJdbcTemplate().update("""
                insert into wm_ads_inventory_snapshot (
                    id, import_file_id, snapshot_at, sku, parent_sku, item_id, item_name,
                    available_units, reserved_units, inbound_units
                ) values (?, 3, timestamp '2026-07-22 12:00:00', ?, ?, ?, ?, ?, ?, ?)
                """, id, sku, parent, "ITEM-" + id, sku + " inventory", available, reserved, inbound);
    }
}
