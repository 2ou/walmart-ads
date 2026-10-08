package com.ai.walmartads.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SupplyChainPropertiesTest {

    @Test
    void requiresEnabledAndCredentials() {
        SupplyChainProperties disabled = properties(false, "token", null, null);
        SupplyChainProperties token = properties(true, "token", null, null);
        SupplyChainProperties account = properties(true, null, "reader", "secret");

        assertThat(disabled.configured()).isFalse();
        assertThat(token.configured()).isTrue();
        assertThat(account.configured()).isTrue();
        assertThat(token.resolvedCron()).isEqualTo("0 0 8 * * *");
        assertThat(token.resolvedZone()).isEqualTo("Asia/Shanghai");
    }

    private static SupplyChainProperties properties(
            boolean enabled,
            String token,
            String username,
            String password
    ) {
        return new SupplyChainProperties(enabled, null, token, username, password,
                null, null, 0, 0, 0);
    }
}
