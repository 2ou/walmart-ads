package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.InventorySnapshot;
import com.ai.walmartads.importing.repository.InventorySnapshotRepository;
import com.ai.walmartads.importing.repository.SkuMappingRepository;
import org.springframework.stereotype.Component;

@Component
public class SkuResolver {

    private final InventorySnapshotRepository inventoryRepository;
    private final SkuMappingRepository mappingRepository;

    public SkuResolver(
            InventorySnapshotRepository inventoryRepository,
            SkuMappingRepository mappingRepository
    ) {
        this.inventoryRepository = inventoryRepository;
        this.mappingRepository = mappingRepository;
    }

    public ResolvedSku resolve(String itemId, String sourceSku) {
        if (sourceSku != null && !sourceSku.isBlank()) {
            return new ResolvedSku(sourceSku, ImportValues.parentSku(sourceSku));
        }
        if (itemId == null || itemId.isBlank()) {
            return new ResolvedSku(null, null);
        }
        return mappingRepository.findFirstByItemIdAndCurrentVersionTrueOrderBySourceUpdatedAtDesc(itemId)
                .map(mapping -> new ResolvedSku(mapping.getSku(), mapping.getParentSku()))
                .or(() -> inventoryRepository.findLatestByItemId(itemId)
                .map(snapshot -> new ResolvedSku(snapshot.getSku(), snapshot.getParentSku()))
                )
                .orElseGet(() -> new ResolvedSku(null, null));
    }

    public record ResolvedSku(String sku, String parentSku) {
    }
}
