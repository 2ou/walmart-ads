package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.repository.InventorySnapshotRepository;
import com.ai.walmartads.importing.repository.ProductRecordRepository;
import com.ai.walmartads.importing.repository.SkuMappingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SkuResolver {

    private final InventorySnapshotRepository inventoryRepository;
    private final SkuMappingRepository mappingRepository;
    private final ProductRecordRepository productRepository;

    @Autowired
    public SkuResolver(
            InventorySnapshotRepository inventoryRepository,
            SkuMappingRepository mappingRepository,
            ProductRecordRepository productRepository
    ) {
        this.inventoryRepository = inventoryRepository;
        this.mappingRepository = mappingRepository;
        this.productRepository = productRepository;
    }

    /** Compatibility constructor for the retired CSV parser tests. */
    public SkuResolver(
            InventorySnapshotRepository inventoryRepository,
            SkuMappingRepository mappingRepository
    ) {
        this(inventoryRepository, mappingRepository, null);
    }

    /** Resolves from the live listing-enriched product table before other product-library mappings. */
    public ResolvedSku resolveCatalog(String itemId, String sourceSku) {
        if (productRepository == null) {
            return new ResolvedSku(null, null);
        }
        if (itemId != null && !itemId.isBlank()) {
            ResolvedSku byItem = productRepository
                    .findFirstByProductIdAndCurrentVersionTrueOrderBySourceUpdatedAtDesc(itemId)
                    .map(product -> new ResolvedSku(
                            product.getSku(),
                            ImportValues.parentSku(product.getSku())))
                    .orElse(null);
            if (byItem != null) {
                return byItem;
            }
        }
        if (sourceSku != null && !sourceSku.isBlank()) {
            return productRepository
                    .findFirstBySkuAndCurrentVersionTrueOrderBySourceUpdatedAtDesc(sourceSku)
                    .map(product -> new ResolvedSku(
                            product.getSku(),
                            ImportValues.parentSku(product.getSku())))
                    .orElseGet(() -> new ResolvedSku(null, null));
        }
        return new ResolvedSku(null, null);
    }

    public ResolvedSku resolve(String itemId, String sourceSku) {
        ResolvedSku catalog = resolveCatalog(itemId, sourceSku);
        if (catalog.sku() != null) {
            return catalog;
        }
        if (sourceSku != null && !sourceSku.isBlank()) {
            return new ResolvedSku(sourceSku, ImportValues.parentSku(sourceSku));
        }
        if (itemId == null || itemId.isBlank()) {
            return new ResolvedSku(null, null);
        }
        return mappingRepository.findFirstByItemIdAndCurrentVersionTrueOrderBySourceUpdatedAtDesc(itemId)
                .map(mapping -> new ResolvedSku(mapping.getSku(), mapping.getParentSku()))
                .or(() -> inventoryRepository.findLatestByItemId(itemId).stream()
                .findFirst()
                .map(row -> new ResolvedSku((String) row[0], (String) row[1]))
                )
                .orElseGet(() -> new ResolvedSku(null, null));
    }

    public record ResolvedSku(String sku, String parentSku) {
    }
}
