package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.SkuMapping;
import com.ai.walmartads.importing.domain.ProductRecord;
import com.ai.walmartads.importing.repository.InventorySnapshotRepository;
import com.ai.walmartads.importing.repository.ProductRecordRepository;
import com.ai.walmartads.importing.repository.SkuMappingRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SkuResolverTest {

    @Test
    void resolvesLiveAdsOnlyFromCurrentProductCatalog() {
        InventorySnapshotRepository inventoryRepository = mock(InventorySnapshotRepository.class);
        SkuMappingRepository mappingRepository = mock(SkuMappingRepository.class);
        ProductRecordRepository productRepository = mock(ProductRecordRepository.class);
        ProductRecord product = mock(ProductRecord.class);
        when(product.getSku()).thenReturn("PNK361002-C-Black-M-WFS");
        when(productRepository.findFirstByProductIdAndCurrentVersionTrueOrderBySourceUpdatedAtDesc("5479331637"))
                .thenReturn(Optional.of(product));

        SkuResolver.ResolvedSku resolved = new SkuResolver(
                inventoryRepository,
                mappingRepository,
                productRepository
        ).resolveCatalog("5479331637", null);

        assertThat(resolved.sku()).isEqualTo("PNK361002-C-Black-M-WFS");
        assertThat(resolved.parentSku()).isEqualTo("PNK361002");
    }

    @Test
    void resolvesItemIdFromCurrentWfsMappingBeforeInventory() {
        InventorySnapshotRepository inventoryRepository = mock(InventorySnapshotRepository.class);
        SkuMappingRepository mappingRepository = mock(SkuMappingRepository.class);
        SkuMapping mapping = mock(SkuMapping.class);
        when(mapping.getSku()).thenReturn("PNK361002-C-Black-M-WFS");
        when(mapping.getParentSku()).thenReturn("PNK361002");
        when(mappingRepository.findFirstByItemIdAndCurrentVersionTrueOrderBySourceUpdatedAtDesc("5479331637"))
                .thenReturn(Optional.of(mapping));

        SkuResolver.ResolvedSku resolved = new SkuResolver(
                inventoryRepository,
                mappingRepository
        ).resolve("5479331637", null);

        assertThat(resolved.sku()).isEqualTo("PNK361002-C-Black-M-WFS");
        assertThat(resolved.parentSku()).isEqualTo("PNK361002");
    }
}
