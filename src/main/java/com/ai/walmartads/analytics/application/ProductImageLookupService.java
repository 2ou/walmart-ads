package com.ai.walmartads.analytics.application;

import com.ai.walmartads.common.WalmartImageUrl;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** Lightweight parent-SKU image lookup for analytics views. */
@Service
public class ProductImageLookupService {

    private final NamedParameterJdbcTemplate jdbc;

    public ProductImageLookupService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, String> loadParentImages() {
        Map<String, String> images = new LinkedHashMap<>();
        jdbc.queryForList("""
                select product.parent_sku,
                       max(product.main_image_url) as image_url
                  from wm_ads_product_record product
                  join wm_ads_import_file file
                    on file.id = product.import_file_id and file.active = true
                 where product.current_version = true
                   and product.parent_sku is not null
                   and product.parent_sku <> ''
                 group by product.parent_sku
                """, new MapSqlParameterSource()).forEach(row -> {
            String parentSku = text(row.get("parent_sku"));
            String imageUrl = WalmartImageUrl.thumb(text(row.get("image_url")));
            if (!parentSku.isBlank() && !imageUrl.isBlank()) {
                images.put(parentSku, imageUrl);
            }
        });
        return images;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
