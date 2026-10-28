package com.ai.walmartads.connector;

import com.ai.walmartads.inventory.application.LingxingDomesticInventoryService;
import com.ai.walmartads.inventory.application.LingxingWfsShipmentService;
import com.ai.walmartads.supplychain.SupplyChainSyncService;
import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspaceLingXingConfigService;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

/**
 * Connects the signed-in workbench tab to an extension running in the same Chrome profile.
 * The browser receives a random workspace-scoped key; only its SHA-256 digest is stored.
 */
@Service
public class CurrentBrowserBridgeService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    private final JdbcTemplate jdbc;
    private final WorkspaceLingXingConfigService workspaces;
    private final DataConnectorStore connectorStore;
    private final DataConnectorSessionService sessions;
    private final LingxingDomesticInventoryService inventory;
    private final LingxingWfsShipmentService wfs;
    private final SupplyChainSyncService supplyChain;
    private final com.ai.walmartads.config.DataConnectorProperties connectorProperties;

    public CurrentBrowserBridgeService(
            JdbcTemplate jdbc,
            WorkspaceLingXingConfigService workspaces,
            DataConnectorStore connectorStore,
            DataConnectorSessionService sessions,
            LingxingDomesticInventoryService inventory,
            LingxingWfsShipmentService wfs,
            SupplyChainSyncService supplyChain,
            com.ai.walmartads.config.DataConnectorProperties connectorProperties
    ) {
        this.jdbc = jdbc;
        this.workspaces = workspaces;
        this.connectorStore = connectorStore;
        this.sessions = sessions;
        this.inventory = inventory;
        this.wfs = wfs;
        this.supplyChain = supplyChain;
        this.connectorProperties = connectorProperties;
    }

    public Bootstrap bootstrap(WorkspacePrincipal principal) {
        connectorStore.ensureWorkspace(principal);
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("""
                insert into wm_ads_browser_bridge(
                    workspace_id,bridge_key_hash,extension_id,store_abbreviation,status,
                    connected_at,last_seen_at,last_message
                ) values (?,?,null,?,'WAITING',null,null,'等待当前 Chrome 扩展响应')
                on duplicate key update bridge_key_hash=values(bridge_key_hash),extension_id=null,
                    store_abbreviation=values(store_abbreviation),status='WAITING',
                    connected_at=null,last_seen_at=null,last_message='等待当前 Chrome 扩展响应'
                """, principal.workspaceId(), hash(key), principal.storeAbbreviation());
        List<String> sources = principal.supplyChainEnabled()
                ? List.of(ConnectorSource.LINGXING_INVENTORY.name(), ConnectorSource.LINGXING_WFS.name(),
                ConnectorSource.SUPPLY_CHAIN.name())
                : List.of(ConnectorSource.LINGXING_INVENTORY.name(), ConnectorSource.LINGXING_WFS.name());
        return new Bootstrap(key, principal.workspaceId(), principal.storeName(),
                principal.storeAbbreviation(), sources);
    }

    public WorkspacePrincipal authenticate(String rawKey, String extensionId) {
        if (!StringUtils.hasText(rawKey) || !StringUtils.hasText(extensionId)) {
            throw new BridgeAuthenticationException("当前 Chrome 尚未与工作台连接");
        }
        List<BridgeAuthRow> rows = jdbc.query("""
                select b.workspace_id,b.extension_id,w.username,w.status,w.store_id,w.store_name,
                       w.store_abbreviation,w.supply_chain_enabled
                  from wm_ads_browser_bridge b
                  join wm_ads_workspace w on w.id=b.workspace_id
                 where b.bridge_key_hash=? and w.status='ACTIVE'
                """, (rs, rowNum) -> new BridgeAuthRow(
                rs.getLong("workspace_id"), rs.getString("extension_id"), rs.getString("username"),
                rs.getString("status"), rs.getString("store_id"), rs.getString("store_name"),
                rs.getString("store_abbreviation"), rs.getBoolean("supply_chain_enabled")), hash(rawKey));
        if (rows.isEmpty()) throw new BridgeAuthenticationException("工作台连接已更新，请刷新工作台页面后重试");
        BridgeAuthRow row = rows.getFirst();
        if (StringUtils.hasText(row.extensionId()) && !row.extensionId().equals(extensionId)) {
            throw new BridgeAuthenticationException("连接令牌已绑定到另一扩展实例");
        }
        jdbc.update("""
                update wm_ads_browser_bridge
                   set extension_id=coalesce(extension_id,?),status='CONNECTED',connected_at=coalesce(connected_at,now(6)),
                       last_seen_at=now(6),last_message='当前 Chrome 已连接'
                 where workspace_id=?
                """, extensionId, row.workspaceId());
        return new WorkspacePrincipal(row.workspaceId(), row.username(), row.status(), row.storeId(),
                row.storeName(), row.storeAbbreviation(), row.supplyChainEnabled(), 0L);
    }

    public BridgeStatus status(long workspaceId) {
        List<BridgeStatus> rows = jdbc.query("""
                select status,extension_id,connected_at,last_seen_at,last_message,
                       last_seen_at is not null and last_seen_at>=date_sub(now(6),interval 3 minute) as online
                  from wm_ads_browser_bridge where workspace_id=?
                """, (rs, rowNum) -> new BridgeStatus(
                rs.getBoolean("online"), rs.getString("status"), rs.getString("extension_id"),
                rs.getObject("connected_at", LocalDateTime.class),
                rs.getObject("last_seen_at", LocalDateTime.class), rs.getString("last_message")), workspaceId);
        return rows.isEmpty()
                ? new BridgeStatus(false, "NOT_INSTALLED", null, null, null, "等待当前 Chrome 扩展连接")
                : rows.getFirst();
    }

    public BridgeStatus heartbeat(WorkspacePrincipal principal) {
        return status(principal.workspaceId());
    }

    public void saveSupplyCredential(WorkspacePrincipal principal, String value) {
        String credential = value == null ? "" : value.trim();
        if (!credential.regionMatches(true, 0, "Bearer ", 0, 7) || credential.length() <= 16) {
            throw new IllegalArgumentException("供应链登录凭证格式无效");
        }
        sessions.save(principal.workspaceId(), ConnectorSystem.SUPPLY_CHAIN, null, credential,
                true, "已从当前 Chrome 登录会话更新凭证");
        touch(principal.workspaceId(), "已识别当前 Chrome 的供应链登录会话");
    }

    public SyncResult syncSupply(WorkspacePrincipal principal, String triggeredBy) {
        if (!principal.supplyChainEnabled()) throw new IllegalArgumentException("当前店铺未启用供应链同步");
        long runId = connectorStore.startRun(principal.workspaceId(), ConnectorSource.SUPPLY_CHAIN, triggeredBy);
        SupplyChainSyncService.SyncSummary summary = WorkspaceContext.callAs(principal,
                () -> supplyChain.syncNow("CURRENT_CHROME_" + triggeredBy));
        if (!"SUCCESS".equals(summary.status())) {
            String message = StringUtils.hasText(summary.message()) ? summary.message() : "供应链同步失败";
            connectorStore.finishRun(runId, principal.workspaceId(), ConnectorSource.SUPPLY_CHAIN,
                    "FAILED", 0, message);
            throw new IllegalStateException(message);
        }
        int count = summary.orderCount() + summary.productionCount()
                + summary.deliveryDetailCount() + summary.logisticsCount();
        String message = "订单 " + summary.orderCount() + "、生产 " + summary.productionCount()
                + "、发货 " + summary.deliveryDetailCount() + "、物流 " + summary.logisticsCount();
        connectorStore.finishRun(runId, principal.workspaceId(), ConnectorSource.SUPPLY_CHAIN,
                "SUCCESS", count, message);
        touch(principal.workspaceId(), "供应链数据已从当前 Chrome 同步");
        return new SyncResult(count, message);
    }

    public SyncResult importInventory(WorkspacePrincipal principal, String sourceUrl,
                                      JsonNode response, LocalDateTime capturedAt, String triggeredBy,
                                      String requestBody) {
        dumpRequest("LX-INVENTORY", requestBody, sourceUrl);
        assertStoreSelected(requestBody, principal.storeId());
        long runId = connectorStore.startRun(principal.workspaceId(), ConnectorSource.LINGXING_INVENTORY, triggeredBy);
        try {
            var summary = WorkspaceContext.callAs(principal,
                    () -> inventory.replaceFromConnector(sourceUrl, response, capturedAt));
            String message = "同步 " + summary.importedSkus() + " 个 SKU，可用库存 " + summary.availableUnits();
            connectorStore.finishRun(runId, principal.workspaceId(), ConnectorSource.LINGXING_INVENTORY,
                    "SUCCESS", summary.importedSkus(), message);
            sessions.save(principal.workspaceId(), ConnectorSystem.LINGXING, null, null,
                    true, "已识别当前 Chrome 的领星登录会话");
            touch(principal.workspaceId(), "领星国内仓库存已从当前 Chrome 同步");
            return new SyncResult(summary.importedSkus(), message);
        } catch (RuntimeException exception) {
            connectorStore.finishRun(runId, principal.workspaceId(), ConnectorSource.LINGXING_INVENTORY,
                    "FAILED", 0, rootMessage(exception));
            throw exception;
        }
    }

    public SyncResult importWfs(WorkspacePrincipal principal, String sourceUrl,
                                JsonNode response, LocalDateTime capturedAt, String triggeredBy) {
        long runId = connectorStore.startRun(principal.workspaceId(), ConnectorSource.LINGXING_WFS, triggeredBy);
        try {
            var summary = WorkspaceContext.callAs(principal,
                    () -> wfs.replaceFromConnector(sourceUrl, response, capturedAt));
            String message = "同步 " + summary.importedShipments() + " 个货件、"
                    + summary.importedRows() + " 行明细";
            connectorStore.finishRun(runId, principal.workspaceId(), ConnectorSource.LINGXING_WFS,
                    "SUCCESS", summary.importedRows(), message);
            sessions.save(principal.workspaceId(), ConnectorSystem.LINGXING, null, null,
                    true, "已识别当前 Chrome 的领星登录会话");
            touch(principal.workspaceId(), "领星 WFS 货件已从当前 Chrome 同步");
            return new SyncResult(summary.importedRows(), message);
        } catch (RuntimeException exception) {
            connectorStore.finishRun(runId, principal.workspaceId(), ConnectorSource.LINGXING_WFS,
                    "FAILED", 0, rootMessage(exception));
            throw exception;
        }
    }

    public void recordFailure(WorkspacePrincipal principal, ConnectorSource source, String message, boolean loginRequired) {
        long runId = connectorStore.startRun(principal.workspaceId(), source, "CURRENT_CHROME");
        String status = loginRequired ? "LOGIN_REQUIRED" : "FAILED";
        connectorStore.finishRun(runId, principal.workspaceId(), source, status, 0, message);
        if (loginRequired) {
            sessions.requireLogin(principal.workspaceId(), source.system(), message,
                    source.system() == ConnectorSystem.SUPPLY_CHAIN);
        }
        touch(principal.workspaceId(), message);
    }

    /**
     * 把扩展重放时用的真实请求体落盘。国内仓库存的返回范围完全由这个 body 里的筛选条件决定，
     * 页面上的搜索/仓库选择一旦变化，结果集就会从「PNK 的 1130 行」变成「全库 63 万行的第 1 页」。
     */
    private void dumpRequest(String tag, String requestBody, String sourceUrl) {
        if (connectorProperties == null || !StringUtils.hasText(requestBody)) return;
        try {
            java.nio.file.Path dir = connectorProperties.resolvedDataDir().resolve("diagnostics");
            java.nio.file.Files.createDirectories(dir);
            String stamp = LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            String content = "// capturedAt=" + LocalDateTime.now() + "\n// sourceUrl=" + sourceUrl
                    + "\n" + requestBody;
            java.nio.file.Files.writeString(
                    dir.resolve(tag.toLowerCase(java.util.Locale.ROOT) + "-request-" + stamp + ".json"),
                    content, StandardCharsets.UTF_8);
        } catch (Exception exception) {
            // 仅诊断用途，失败不影响同步
        }
    }

    /**
     * 领星「仓库明细」是所有店铺混装的列表（全库 63 万行）。页面上的店铺筛选（sid_list）决定
     * 返回哪些 SKU；没选中本店铺时抓回来的是别人的库存，必须直接失败而不是记成本店铺的。
     */
    private void assertStoreSelected(String requestBody, String storeId) {
        if (!StringUtils.hasText(requestBody) || !StringUtils.hasText(storeId)) return;
        JsonNode field;
        try {
            field = JSON_MAPPER.readTree(requestBody).get("sid_list");
        } catch (Exception ignored) {
            return; // 不是 JSON 请求体，交给下游的库存校验兜底
        }
        if (field == null || field.isNull()) return;
        StringBuilder buffer = new StringBuilder();
        if (field.isArray()) {
            field.forEach(node -> buffer.append(node.asText("")).append(','));
        } else {
            buffer.append(field.asText(""));
        }
        String value = buffer.toString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(
                    "领星库存页面没有选中店铺（sid_list 为空），返回的是全部店铺的库存，"
                            + "请把页面上的店铺切到本店铺后重新同步，已保留原库存");
        }
        if (!value.contains(storeId)) {
            throw new IllegalArgumentException("领星库存页面当前筛选的不是本店铺（sid_list=" + value
                    + "，本店铺 " + storeId + "），已保留原库存");
        }
    }

    private void touch(long workspaceId, String message) {
        jdbc.update("""
                update wm_ads_browser_bridge set status='CONNECTED',last_seen_at=now(6),last_message=?
                 where workspace_id=?
                """, truncate(message, 1000), workspaceId);
    }

    private WorkspacePrincipal principal(long workspaceId) {
        return workspaces.connectorWorkspaces().stream()
                .filter(candidate -> candidate.workspaceId() == workspaceId)
                .findFirst().orElseThrow(() -> new BridgeAuthenticationException("店铺已停用或不存在"));
    }

    public WorkspacePrincipal authenticateAndReload(String rawKey, String extensionId) {
        WorkspacePrincipal authenticated = authenticate(rawKey, extensionId);
        return principal(authenticated.workspaceId());
    }

    private static String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return StringUtils.hasText(current.getMessage()) ? current.getMessage() : current.getClass().getSimpleName();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public record Bootstrap(String key, long workspaceId, String storeName, String storeAbbreviation,
                            List<String> sources) {
    }

    public record BridgeStatus(boolean online, String status, String extensionId,
                               LocalDateTime connectedAt, LocalDateTime lastSeenAt, String message) {
    }

    public record SyncResult(int recordCount, String message) {
    }

    private record BridgeAuthRow(long workspaceId, String extensionId, String username, String status,
                                 String storeId, String storeName, String storeAbbreviation,
                                 boolean supplyChainEnabled) {
    }

    public static class BridgeAuthenticationException extends RuntimeException {
        public BridgeAuthenticationException(String message) {
            super(message);
        }
    }
}
