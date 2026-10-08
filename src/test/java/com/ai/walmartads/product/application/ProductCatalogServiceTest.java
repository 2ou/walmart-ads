package com.ai.walmartads.product.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.walmartads.inventory.application.ParentLifecycleService;
import com.ai.walmartads.inventory.repository.ParentLifecycleRepository;
import com.ai.walmartads.product.application.ProfitAssumptionRepository;
import com.ai.walmartads.product.application.ProfitCalculator;
import com.ai.walmartads.product.application.RefundRateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProductCatalogServiceTest {

    private EmbeddedDatabase database;
    private ProductCatalogService service;

    @BeforeEach
    void setUp() {
        database = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build();
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(database);
        jdbc.getJdbcTemplate().execute("""
                create table wm_ads_import_file (
                    id bigint primary key,
                    file_name varchar(500) not null,
                    active boolean not null,
                    created_at timestamp not null
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                create table wm_ads_product_record (
                    id bigint primary key,
                    import_file_id bigint not null,
                    current_version boolean not null,
                    sku varchar(180) not null,
                    parent_sku varchar(80),
                    product_type varchar(160),
                    product_id varchar(80),
                    product_id_type varchar(40),
                    product_name text,
                    brand varchar(120),
                    short_description text,
                    key_features text,
                    main_image_url varchar(2500),
                    secondary_image_urls text,
                    color varchar(600),
                    color_category varchar(600),
                    size varchar(120),
                    size_group varchar(120),
                    pattern_name varchar(600),
                    material_name varchar(4000),
                    gender varchar(80),
                    variant_group_id varchar(300),
                    cutting_yield integer,
                    launch_date date,
                    price decimal(18,6),
                    strikethrough_price decimal(18,6),
                    upc varchar(40),
                    gtin varchar(40),
                    listing_status varchar(40),
                    store varchar(40),
                    delivery_fee decimal(18,6),
                    raw_payload text,
                    source_updated_at timestamp
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                create table wm_ads_profit_assumption (
                    parent_sku varchar(80) primary key,
                    purchase_cost decimal(18,6),
                    first_leg decimal(18,6),
                    sales_qty decimal(18,6),
                    ad_cost decimal(18,6),
                    refund_rate decimal(10,6),
                    prefer_manual_refund_rate boolean not null default false,
                    delivery_fee decimal(18,6)
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                create table wm_ads_sku_mapping (
                    item_id varchar(40),
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
                    quantity decimal(18,6),
                    item_cost decimal(18,6),
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
        jdbc.getJdbcTemplate().execute("""
                create table wm_ads_return_order (
                    id bigint primary key,
                    import_file_id bigint not null,
                    business_key varchar(64) not null,
                    parent_sku varchar(80),
                    item_id varchar(40),
                    current_status varchar(80),
                    quantity decimal(18,6),
                    report_month date
                )
                """);
        jdbc.getJdbcTemplate().update(
                "insert into wm_ads_import_file (id, file_name, active, created_at) values (1, 'PNK100-upload.xlsx', true, timestamp '2026-07-01 00:00:00')"
        );
        jdbc.getJdbcTemplate().update("""
                insert into wm_ads_product_record (
                    id, import_file_id, current_version, sku, parent_sku, product_type,
                    product_id, product_id_type, product_name, brand, short_description,
                    key_features, main_image_url, secondary_image_urls, color, color_category,
                    size, size_group, pattern_name, material_name, gender, variant_group_id,
                    raw_payload, source_updated_at
                ) values (
                    1, 1, true, 'PNK100-Blue-M-WFS', 'PNK100', 'Shirt',
                    '123', 'UPC', 'Blue listing title', 'PINKSIR', 'Listing description',
                    '[\"Soft fabric\",\"Daily wear\"]', 'https://img/main.jpg',
                    '[\"https://img/side.jpg\"]', 'Blue', 'Blue', 'M', 'Regular',
                    'Solid', 'Cotton', 'Female', 'PNK100',
                    '{"isPrimaryVariant":"No","swatchImageUrl":"https://img/swatch.jpg","clothingFit":"Relaxed"}',
                    timestamp '2026-07-24 12:00:00'
                )
                """);
        jdbc.getJdbcTemplate().update("""
                insert into wm_ads_product_record (
                    id, import_file_id, current_version, sku, parent_sku, product_type,
                    product_id, product_id_type, product_name, brand, short_description,
                    key_features, main_image_url, secondary_image_urls, color, color_category,
                    size, size_group, pattern_name, material_name, gender, variant_group_id,
                    raw_payload, source_updated_at
                ) values (
                    2, 1, true, 'PNK100-Blue-XL-WFS', 'PNK100', 'Shirt',
                    '124', 'UPC', 'Blue listing title', 'PINKSIR', 'Listing description',
                    '[\"Soft fabric\",\"Daily wear\"]', 'https://img/main.jpg',
                    '[\"https://img/side.jpg\"]', 'Blue', 'Blue', 'XL', 'Regular',
                    'Solid', 'Cotton', 'Female', 'PNK100',
                    '{"isPrimaryVariant":"Yes","swatchImageUrl":"https://img/swatch.jpg","clothingFit":"Relaxed"}',
                    timestamp '2026-07-24 12:00:00'
                )
                """);
        jdbc.getJdbcTemplate().update("""
                insert into wm_ads_product_record (
                    id, import_file_id, current_version, sku, parent_sku, product_type,
                    product_id, product_id_type, product_name, brand, short_description,
                    key_features, main_image_url, secondary_image_urls, color, color_category,
                    size, size_group, pattern_name, material_name, gender, variant_group_id,
                    raw_payload, source_updated_at
                ) values (
                    3, 1, true, 'PNK100-Black-S-WFS', 'PNK100', 'Shirt',
                    '125', 'UPC', 'Black listing title', 'PINKSIR', 'Listing description',
                    '[\"Soft fabric\",\"Daily wear\"]', 'https://img/black-main.jpg',
                    '[\"https://img/black-side.jpg\"]', 'Black', 'Black', 'S', 'Regular',
                    'Solid', 'Cotton', 'Female', 'PNK100',
                    '{"isPrimaryVariant":"No","swatchImageUrl":"https://img/black-swatch.jpg"}',
                    timestamp '2026-07-24 12:00:00'
                )
                """);
        jdbc.getJdbcTemplate().update(
                "update wm_ads_product_record set price = 12.99, strikethrough_price = 15.99 where id = 1"
        );
        service = new ProductCatalogService(
                jdbc,
                new ObjectMapper(),
                new ProfitCalculator(),
                (ProfitAssumptionRepository) null,
                new RefundRateService(jdbc),
                null,
                new ParentLifecycleService(mock(ParentLifecycleRepository.class))
        );
    }

    @Test
    void groupsVariantsAndBuildsListingDetail() {
        ProductCatalogService.CatalogPayload catalog = service.load();
        ProductCatalogService.ProductDetail detail = service.detail("PNK100");

        assertThat(catalog.meta().parentProductCount()).isEqualTo(1);
        assertThat(catalog.meta().variantCount()).isEqualTo(3);
        assertThat(catalog.products().getFirst().strikethroughPrice()).isEqualByComparingTo("15.99");
        assertThat(catalog.products().getFirst().searchTerms()).contains(
                "PNK100-Blue-M-WFS",
                "124",
                "Blue",
                "XL"
        );
        assertThat(detail.keyFeatures()).containsExactly("Soft fabric", "Daily wear");
        assertThat(detail.images()).contains(
                "https://img/main.jpg",
                "https://img/swatch.jpg",
                "https://img/side.jpg"
        );
        assertThat(detail.attributes()).containsEntry("版型", "Relaxed");
        assertThat(detail.variants().getFirst().primaryVariant()).isTrue();
        assertThat(detail.variants())
                .anySatisfy(variant -> assertThat(variant.strikethroughPrice()).isEqualByComparingTo("15.99"));
        assertThat(detail.colorGroups()).hasSize(2);
        assertThat(detail.colorGroups().getFirst().color()).isEqualTo("Blue");
        assertThat(detail.colorGroups().getFirst().primaryColor()).isTrue();
        assertThat(detail.colorGroups().getFirst().sizes()).containsExactly("M", "XL");
        assertThat(detail.colorGroups().getFirst().images())
                .containsExactly("https://img/main.jpg", "https://img/side.jpg");
        assertThat(detail.colorGroups().get(1).color()).isEqualTo("Black");
        assertThat(detail.colorGroups().get(1).variants())
                .extracting(ProductCatalogService.Variant::size)
                .containsExactly("S");
        ObjectMapper responseMapper = new ObjectMapper().findAndRegisterModules();
        assertThat(responseMapper.valueToTree(catalog).toString())
                .doesNotContain(
                        "minimumPrice",
                        "maximumPrice",
                        "listingCompleteness",
                        "completeListingVariants",
                        "variantsWithMainImage"
                );
        assertThat(responseMapper.valueToTree(detail).toString())
                .doesNotContain("minimumPrice", "maximumPrice");
    }

    @Test
    void usesCumulativeRefundRateByDefaultAndAllowsManualOverride() {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(database);
        jdbc.getJdbcTemplate().update("""
                insert into wm_ads_profit_assumption (
                    parent_sku, purchase_cost, first_leg, sales_qty, ad_cost, refund_rate
                ) values ('PNK100', 10, 2, 100, 1, 0.12)
                """);
        jdbc.getJdbcTemplate().update("""
                insert into wm_ads_order_record (import_file_id, sku, parent_sku, quantity, item_cost, order_date)
                values (1, 'PNK100-Blue-M-WFS', 'PNK100', 10, 0, date '2026-07-01')
                """);
        jdbc.getJdbcTemplate().update("""
                insert into wm_ads_return_order (
                    id, import_file_id, business_key, parent_sku, item_id, current_status, quantity, report_month
                ) values (1, 1, 'PNK100-RMA-1', 'PNK100', null, 'RETURN_INVOICED', 2, date '2026-07-01')
                """);

        ProductCatalogService.ProductDetail detail = service.detail("PNK100");

        assertThat(detail.refundRate()).isEqualByComparingTo(new BigDecimal("0.20"));
        assertThat(detail.refundRateSource()).isEqualTo("CUMULATIVE");
        assertThat(detail.historicalRefundRate()).isEqualByComparingTo(new BigDecimal("0.20"));
        assertThat(detail.attributes()).containsEntry("款号累计总退款率", "20.00%");

        jdbc.getJdbcTemplate().update("update wm_ads_profit_assumption set prefer_manual_refund_rate = true where parent_sku = 'PNK100'");
        ProductCatalogService.ProductDetail manualDetail = service.detail("PNK100");

        assertThat(manualDetail.refundRate()).isEqualByComparingTo(new BigDecimal("0.12"));
        assertThat(manualDetail.refundRateSource()).isEqualTo("MANUAL");
    }
}
