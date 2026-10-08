package com.ai.walmartads.analytics.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProductImageLookupServiceTest {

    private NamedParameterJdbcTemplate jdbc;
    private ProductImageLookupService service;

    @BeforeEach
    void setUp() {
        jdbc = new NamedParameterJdbcTemplate(new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true)
                .build());
        jdbc.getJdbcTemplate().execute("""
                create table wm_ads_import_file (
                    id bigint primary key,
                    active boolean not null
                )
                """);
        jdbc.getJdbcTemplate().execute("""
                create table wm_ads_product_record (
                    id bigint primary key,
                    import_file_id bigint not null,
                    current_version boolean not null,
                    parent_sku varchar(80),
                    main_image_url varchar(2500)
                )
                """);
        jdbc.getJdbcTemplate().update("insert into wm_ads_import_file values (1, true), (2, false)");
        jdbc.getJdbcTemplate().update("""
                insert into wm_ads_product_record values
                    (1, 1, true, 'PNK100', 'https://i5.walmartimages.com/asr/example.jpeg'),
                    (2, 1, false, 'PNK200', 'https://i5.walmartimages.com/asr/old.jpeg'),
                    (3, 2, true, 'PNK300', 'https://i5.walmartimages.com/asr/inactive.jpeg')
                """);
        service = new ProductImageLookupService(jdbc);
    }

    @Test
    void returnsOnlyActiveCurrentProductImagesAsThumbnails() {
        Map<String, String> images = service.loadParentImages();

        assertThat(images).containsOnlyKeys("PNK100");
        assertThat(images.get("PNK100"))
                .contains("odnWidth=200")
                .contains("odnHeight=200")
                .contains("odnBg=FFFFFF");
    }
}
