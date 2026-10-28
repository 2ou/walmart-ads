package com.ai.walmartads.workspace;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LingXingMcpCoordinatorTest {

    @Test
    void scopeIsStableAndNeverContainsTheCredential() {
        String key = "secret-mcp-key-that-must-not-appear-in-logs";

        LingXingMcpCoordinator.Scope first = LingXingMcpCoordinator.scope(
                "https://openmcp.lingxing.com/mcp-servers/lingxing-mcp", key);
        LingXingMcpCoordinator.Scope second = LingXingMcpCoordinator.scope(
                " HTTPS://OPENMCP.LINGXING.COM/MCP-SERVERS/LINGXING-MCP ", key);

        assertThat(first).isEqualTo(second);
        assertThat(first.hash()).hasSize(64).doesNotContain(key);
        assertThat(first.fingerprint()).startsWith("mcp-").doesNotContain(key);
    }
}
