package com.ai.walmartads.importing.parser;

import com.ai.walmartads.importing.domain.InventorySnapshot;
import com.ai.walmartads.importing.repository.InventorySnapshotRepository;
import org.springframework.stereotype.Component;

@Component
public class SkuResolver {

    private final InventorySnapshotRepository inventoryRepository;

    public SkuResolver(InventorySnapshotRepository inventoryRepository) {
        this.inventoryRepository = inventoryRepository;
    }

    public ResolvedSku resolve(String itemId, String sourceSku) {
        if (sourceSku != null && !sourceSku.isBlank()) {
            return new ResolvedSku(sourceSku, ImportValues.parentSku(sourceSku));
        }
        if (itemId == null || itemId.isBlank()) {
            return new ResolvedSku(null, null);
        }
        return inventoryRepository.findLatestByItemId(itemId)
                .map(snapshot -> new ResolvedSku(snapshot.getSku(), snapshot.getParentSku()))
                .orElseGet(() -> new ResolvedSku(null, null));
    }

    public record ResolvedSku(String sku, String parentSku) {
    }
}
