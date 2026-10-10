package com.ai.walmartads.sales.application;

import com.ai.walmartads.inventory.application.TransitInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
                    active boolean not null,
                    report_kind varchar(40)
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
        // V53：订单读路径改走 wm_ads_order_record_valid 视图（只保留产品库 current_version=1 的款）。
        // 夹具不建这张视图，SalesDataService 的三条读 SQL 在 H2 里会直接 BadSqlGrammar。
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
        // 生产侧改读统一视图（旧报表快照 ∪ 领星库存快照，V49）。测试只建旧报表那一路，
        // 领星独有的列在视图里以 NULL 呈现 —— 与 V49 的视图口径保持一致。
        jdbc.getJdbcTemplate().execute("""
                create view wm_ads_inventory_unified as
                select 'LEGACY' as source_type,
                       s.snapshot_at, s.sku, s.parent_sku, s.item_id, s.item_name,
                       s.available_units,
                       cast(null as decimal(18,4)) as reserved_units,
                       cast(null as decimal(18,4)) as inbound_units
                  from wm_ads_inventory_snapshot s
                  join wm_ads_import_file f on f.id = s.import_file_id and f.active = true
                """);
        jdbc.getJdbcTemplate().execute("""
                create table wm_ads_lx_ad_report (
                    id bigint primary key,
                    report_date date not null,
                    report_level varchar(20) not null,
                    ad_spend decimal(18,4) not null,
                    attributed_sales decimal(18,4) not null
                )
                """);
        jdbc.getJdbcTemplate().update("""
                insert into wm_ads_import_file values
                    (1, true, 'ITEM_PERFORMANCE'),
                    (2, true, 'ITEM_PERFORMANCE'),
                    (3, true, 'KEYWORD_PERFORMANCE'),
                    (4, false, 'ITEM_PERFORMANCE')
                """);

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
        // 文件 1：启用中的商品广告报表，参与统计
        insertAd(1, 1, "2026-07-20", 10, 50);
        insertAd(2, 1, "2026-07-21", 5, 10);
        insertAd(3, 1, "2026-07-22", 20, 40);
        // 路途库存改由 TransitInventoryService 提供（原报表 inbound_units 列已下线）。
        TransitInventoryService transitInventoryService = mock(TransitInventoryService.class);
        when(transitInventoryService.load()).thenReturn(new TransitInventoryService.TransitData(
                Map.of("SKU-A", 4.0, "SKU-B", 2.0),
                List.of(),
                List.of(),
                List.of()
        ));
        service = new SalesDataService(jdbc, transitInventoryService);
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
        // reserved_units（预留库存）已随 CSV 报表下线，统一视图里恒为 NULL → 0
        assertThat(skuA.reservedUnits()).isEqualTo(0);
        assertThat(skuA.transitUnits()).isEqualTo(4);
        assertThat(skuA.availableDays()).isEqualTo(6);
        assertThat(skuA.status()).isEqualTo("GROWING");
        assertThat(parent.units()).isEqualTo(8);
        assertThat(parent.availableUnits()).isEqualTo(12);
        assertThat(parent.reservedUnits()).isEqualTo(0);
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

    @Test
    void loadsAnExplicitDateRangeInsteadOfAnchoringToLatestDate() {
        SalesDataService.SalesPayload payload = service.load(
                7,
                LocalDate.of(2026, 7, 18),
                LocalDate.of(2026, 7, 20)
        );

        assertThat(payload.meta().currentStart()).isEqualTo("2026-07-18");
        assertThat(payload.meta().currentEnd()).isEqualTo("2026-07-20");
        assertThat(payload.meta().periodDays()).isEqualTo(3);
        assertThat(payload.summary().units()).isEqualTo(8);
        assertThat(payload.daily()).hasSize(3);
    }

    @Test
    void sumsDailyAdSpendAndRoasFromLingXingItemReports() {
        SalesDataService.SalesPayload payload = service.load(3);
        Map<LocalDate, SalesDataService.DailyRow> byDate = payload.daily().stream()
                .collect(Collectors.toMap(
                        SalesDataService.DailyRow::date,
                        row -> row
                ));

        // 只统计领星 AD_ITEM 日事实，避免活动、广告组和关键词层级重复累计。
        assertThat(byDate.get(LocalDate.of(2026, 7, 20)).adSpend()).isEqualTo(10);
        assertThat(byDate.get(LocalDate.of(2026, 7, 20)).roas()).isEqualTo(5);
        assertThat(byDate.get(LocalDate.of(2026, 7, 21)).adSpend()).isEqualTo(5);
        assertThat(byDate.get(LocalDate.of(2026, 7, 21)).roas()).isEqualTo(2);
        assertThat(byDate.get(LocalDate.of(2026, 7, 22)).adSpend()).isEqualTo(20);
        assertThat(byDate.get(LocalDate.of(2026, 7, 22)).roas()).isEqualTo(2);
    }

    @Test
    void reportsZeroAdSpendAndRoasWhenNoAdReportExists() {
        SalesDataService.SalesPayload payload = service.load(
                7,
                LocalDate.of(2026, 7, 18),
                LocalDate.of(2026, 7, 19)
        );

        // 07-18 / 07-19 有订单但无广告报表，广告费与 RoAS 应为 0 而不是报错
        assertThat(payload.daily()).allSatisfy(row -> {
            assertThat(row.adSpend()).isEqualTo(0);
            assertThat(row.roas()).isEqualTo(0);
        });
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

    private void insertAd(
            long id,
            long fileId,
            String date,
            double spend,
            double adSales
    ) {
        jdbc.getJdbcTemplate().update("""
                insert into wm_ads_lx_ad_report (
                    id, report_date, report_level, ad_spend, attributed_sales
                ) values (?, ?, 'AD_ITEM', ?, ?)
                """, id, date, spend, adSales);
    }
}
