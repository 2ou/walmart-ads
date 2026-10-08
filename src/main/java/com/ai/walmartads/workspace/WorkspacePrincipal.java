package com.ai.walmartads.workspace;

public record WorkspacePrincipal(
        long workspaceId,
        String username,
        String status,
        String storeId,
        String storeName,
        String storeAbbreviation,
        boolean supplyChainEnabled,
        long sessionId
) {
    public boolean ready() {
        return "ACTIVE".equals(status) && storeId != null && !storeId.isBlank();
    }
}
