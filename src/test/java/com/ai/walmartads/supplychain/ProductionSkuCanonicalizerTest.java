package com.ai.walmartads.supplychain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSkuCanonicalizerTest {

    @Test
    void mapsBareWfsSuffixToUniqueNumberedProductSku() {
        ProductionSkuCanonicalizer resolver = new ProductionSkuCanonicalizer(List.of(
                "PNK366076-C-Brown-1X-WFS02",
                "PNK366076-C-Brown-2X-WFS02"
        ));

        assertThat(resolver.resolve("PNK366076-C-Brown-1X-WFS"))
                .isEqualTo("PNK366076-C-Brown-1X-WFS02");
        assertThat(resolver.resolve(" pnk366076-c-brown-2x-wfs "))
                .isEqualTo("PNK366076-C-Brown-2X-WFS02");
    }

    @Test
    void keepsSourceWhenFamilyHasMoreThanOneProductCandidate() {
        ProductionSkuCanonicalizer resolver = new ProductionSkuCanonicalizer(List.of(
                "PNK1-C-Black-S-WFS01",
                "PNK1-C-Black-S-WFS02"
        ));

        assertThat(resolver.resolve("PNK1-C-Black-S-WFS"))
                .isEqualTo("PNK1-C-Black-S-WFS");
    }

    @Test
    void exactMatchAlwaysWins() {
        ProductionSkuCanonicalizer resolver = new ProductionSkuCanonicalizer(List.of(
                "PNK1-C-Black-S-WFS",
                "PNK1-C-Black-S-WFS02"
        ));

        assertThat(resolver.resolve("pnk1-c-black-s-wfs"))
                .isEqualTo("PNK1-C-Black-S-WFS");
    }
}
