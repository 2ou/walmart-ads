package com.ai.walmartads.connector;

import com.ai.walmartads.workspace.WorkspaceContext;
import org.springframework.stereotype.Service;

@Service
public class DataConnectorSessionService {

    private final DataConnectorStore store;
    private final ConnectorCryptoService crypto;

    public DataConnectorSessionService(DataConnectorStore store, ConnectorCryptoService crypto) {
        this.store = store;
        this.crypto = crypto;
    }

    public String browserState(long workspaceId, ConnectorSystem system) {
        DataConnectorStore.SessionRow row = store.session(workspaceId, system);
        return row == null ? null : crypto.decrypt(row.encryptedState());
    }

    public String credential(long workspaceId, ConnectorSystem system) {
        DataConnectorStore.SessionRow row = store.session(workspaceId, system);
        return row == null ? null : crypto.decrypt(row.encryptedCredential());
    }

    public String currentSupplyAuthorization() {
        return credential(WorkspaceContext.requireId(), ConnectorSystem.SUPPLY_CHAIN);
    }

    public boolean currentSupplyConfigured() {
        String credential = currentSupplyAuthorization();
        return credential != null && !credential.isBlank();
    }

    public void save(long workspaceId, ConnectorSystem system, String storageState,
                     String credential, boolean login, String message) {
        store.saveSession(workspaceId, system, crypto.encrypt(storageState),
                crypto.encrypt(credential), message, login);
    }

    public void requireLogin(long workspaceId, ConnectorSystem system, String message,
                             boolean clearCredential) {
        store.invalidateSession(workspaceId, system, message, clearCredential);
    }
}
