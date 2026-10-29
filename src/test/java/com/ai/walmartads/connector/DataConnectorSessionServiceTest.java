package com.ai.walmartads.connector;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataConnectorSessionServiceTest {

    @Mock
    private DataConnectorStore store;

    @Mock
    private ConnectorCryptoService crypto;

    @InjectMocks
    private DataConnectorSessionService service;

    @Test
    void recognizesOnlyTheSameRejectedCredential() {
        when(store.session(1L, ConnectorSystem.SUPPLY_CHAIN)).thenReturn(new DataConnectorStore.SessionRow(
                null, "encrypted", "LOGIN_REQUIRED", null, null, "expired"));
        when(crypto.decrypt("encrypted")).thenReturn("Bearer rejected-token");

        assertThat(service.hasRejectedCredential(1L, ConnectorSystem.SUPPLY_CHAIN)).isTrue();
        assertThat(service.credential(1L, ConnectorSystem.SUPPLY_CHAIN)).isNull();
        assertThat(service.rejectedCredentialMatches(
                1L, ConnectorSystem.SUPPLY_CHAIN, "Bearer rejected-token")).isTrue();
        assertThat(service.rejectedCredentialMatches(
                1L, ConnectorSystem.SUPPLY_CHAIN, "Bearer fresh-token")).isFalse();
    }

    @Test
    void connectedCredentialIsNeverTreatedAsRejected() {
        when(store.session(1L, ConnectorSystem.SUPPLY_CHAIN)).thenReturn(new DataConnectorStore.SessionRow(
                null, "encrypted", "CONNECTED", null, null, "ok"));
        when(crypto.decrypt("encrypted")).thenReturn("Bearer token");

        assertThat(service.hasRejectedCredential(1L, ConnectorSystem.SUPPLY_CHAIN)).isFalse();
        assertThat(service.credential(1L, ConnectorSystem.SUPPLY_CHAIN)).isEqualTo("Bearer token");
        assertThat(service.rejectedCredentialMatches(
                1L, ConnectorSystem.SUPPLY_CHAIN, "Bearer token")).isFalse();
    }
}
