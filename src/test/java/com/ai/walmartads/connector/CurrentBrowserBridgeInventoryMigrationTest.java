package com.ai.walmartads.connector;

import com.ai.walmartads.workspace.WorkspacePrincipal;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentBrowserBridgeInventoryMigrationTest {
    @Test
    void legacyInventoryUploadsAndErrorsCannotOverwriteMcpState() {
        var bridge = new CurrentBrowserBridgeService(null, null, null, null, null, null, null, null);
        var principal = new WorkspacePrincipal(7L, "PINKSIR", "ACTIVE", "store-7", "PINKSIR", "PNK", true, 1L);
        var result = bridge.importInventory(principal, "old source", null, LocalDateTime.now(), "OLD", "");
        assertThat(result.message()).contains("已忽略旧插件");
        bridge.recordFailure(principal, ConnectorSource.LINGXING_INVENTORY, "old failure", true, false);
    }
}
