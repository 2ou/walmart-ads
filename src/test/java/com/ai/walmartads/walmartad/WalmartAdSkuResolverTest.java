package com.ai.walmartads.walmartad;

import com.ai.walmartads.importing.parser.SkuResolver;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WalmartAdSkuResolverTest {

    @Test
    void usesAuditedAliasForRetiredItemId() {
        SkuResolver productResolver = mock(SkuResolver.class);
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(productResolver.resolve("3853544706", null))
                .thenReturn(new SkuResolver.ResolvedSku(null, null));
        when(jdbc.queryForList(anyString(), (Object) anyString())).thenReturn(List.of(Map.of(
                "sku", "PNK366067-C-Blue Gray-XL-WFS02",
                "parent_sku", "PNK366067"
        )));

        SkuResolver.ResolvedSku resolved = new WalmartAdSkuResolver(productResolver, jdbc)
                .resolve("3853544706", null);

        assertThat(resolved.sku()).isEqualTo("PNK366067-C-Blue Gray-XL-WFS02");
        assertThat(resolved.parentSku()).isEqualTo("PNK366067");
    }
}
