package com.ai.walmartads.walmartad;

import com.ai.walmartads.importing.parser.SkuResolver;
import com.ai.walmartads.importing.parser.ImportValues;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Resolves LingXing ad Item IDs without consulting retired manual advertising facts. */
@Component
public class WalmartAdSkuResolver {

    private final SkuResolver productResolver;
    private final JdbcTemplate jdbc;

    public WalmartAdSkuResolver(SkuResolver productResolver, JdbcTemplate jdbc) {
        this.productResolver = productResolver;
        this.jdbc = jdbc;
    }

    public SkuResolver.ResolvedSku resolve(String itemId, String sourceSku) {
        SkuResolver.ResolvedSku resolved = productResolver.resolve(itemId, sourceSku);
        if (resolved.sku() != null && !resolved.sku().isBlank()) {
            return resolved.parentSku() == null || resolved.parentSku().isBlank()
                    ? new SkuResolver.ResolvedSku(resolved.sku(), ImportValues.parentSku(resolved.sku()))
                    : resolved;
        }
        if (itemId == null || itemId.isBlank()) {
            return resolved;
        }
        List<Map<String, Object>> aliases = jdbc.queryForList("""
                select sku, parent_sku
                  from wm_ads_lx_item_sku_alias
                 where item_id = ?
                """, itemId);
        if (aliases.isEmpty()) {
            return resolved;
        }
        Map<String, Object> alias = aliases.getFirst();
        return new SkuResolver.ResolvedSku(
                String.valueOf(alias.get("sku")),
                String.valueOf(alias.get("parent_sku"))
        );
    }
}
