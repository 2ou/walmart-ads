package com.ai.walmartads.connector;

import com.ai.walmartads.workspace.WorkspaceContext;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

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
        return row == null || !"CONNECTED".equals(row.status())
                ? null : crypto.decrypt(row.encryptedCredential());
    }

    public String currentSupplyAuthorization() {
        return credential(WorkspaceContext.requireId(), ConnectorSystem.SUPPLY_CHAIN);
    }

    public boolean currentSupplyConfigured() {
        String credential = currentSupplyAuthorization();
        return credential != null && !credential.isBlank();
    }

    /**
     * 接口明确拒绝过的供应链凭证继续加密保留，但会话状态为 LOGIN_REQUIRED。
     * 旧版扩展重复提交同一枚失效 token 时直接忽略；重新登录产生新 token 后正常覆盖。
     */
    public boolean rejectedCredentialMatches(long workspaceId, ConnectorSystem system, String candidate) {
        DataConnectorStore.SessionRow row = store.session(workspaceId, system);
        if (row == null || !"LOGIN_REQUIRED".equals(row.status())
                || row.encryptedCredential() == null || candidate == null) {
            return false;
        }
        String rejected = crypto.decrypt(row.encryptedCredential());
        return rejected != null && MessageDigest.isEqual(
                rejected.getBytes(StandardCharsets.UTF_8), candidate.getBytes(StandardCharsets.UTF_8));
    }

    public boolean hasRejectedCredential(long workspaceId, ConnectorSystem system) {
        DataConnectorStore.SessionRow row = store.session(workspaceId, system);
        return row != null && "LOGIN_REQUIRED".equals(row.status())
                && row.encryptedCredential() != null && !row.encryptedCredential().isBlank();
    }

    public void save(long workspaceId, ConnectorSystem system, String storageState,
                     String credential, boolean login, String message) {
        store.saveSession(workspaceId, system,
                storageState == null ? null : crypto.encrypt(storageState),
                credential == null ? null : crypto.encrypt(credential), message, login);
    }

    public void requireLogin(long workspaceId, ConnectorSystem system, String message,
                             boolean clearCredential) {
        store.invalidateSession(workspaceId, system, message, clearCredential);
    }
}
