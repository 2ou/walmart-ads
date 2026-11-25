package com.ai.walmartads.connector;

import com.ai.walmartads.config.DataConnectorProperties;
import com.ai.walmartads.inventory.application.LingxingDomesticInventoryService;
import com.ai.walmartads.inventory.application.LingxingDomesticMcpSyncService;
import com.ai.walmartads.supplychain.SupplyChainSyncService;
import com.ai.walmartads.workspace.WorkspaceAccountService;
import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspaceLingXingConfigService;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import jakarta.annotation.PreDestroy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class DataConnectorOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DataConnectorOrchestrator.class);

    private final DataConnectorProperties properties;
    private final DataConnectorStore store;
    private final WorkspaceLingXingConfigService workspaces;
    private final WorkspaceAccountService accounts;
    private final DataConnectorSessionService sessions;
    private final LingxingDomesticInventoryService inventory;
    private final LingxingDomesticMcpSyncService domesticMcp;
    private final SupplyChainSyncService supplyChain;
    private final CurrentBrowserBridgeService currentBrowserBridge;
    private final ExecutorService executor = Executors.newCachedThreadPool(
            Thread.ofPlatform().daemon().name("data-connector-", 0).factory());
    private final Set<Long> syncing = ConcurrentHashMap.newKeySet();
    private final Set<String> loggingIn = ConcurrentHashMap.newKeySet();

    public DataConnectorOrchestrator(
            DataConnectorProperties properties,
            DataConnectorStore store,
            WorkspaceLingXingConfigService workspaces,
            WorkspaceAccountService accounts,
            DataConnectorSessionService sessions,
            LingxingDomesticInventoryService inventory,
            LingxingDomesticMcpSyncService domesticMcp,
            SupplyChainSyncService supplyChain,
            CurrentBrowserBridgeService currentBrowserBridge
    ) {
        this.properties = properties;
        this.store = store;
        this.workspaces = workspaces;
        this.accounts = accounts;
        this.sessions = sessions;
        this.inventory = inventory;
        this.domesticMcp = domesticMcp;
        this.supplyChain = supplyChain;
        this.currentBrowserBridge = currentBrowserBridge;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void catchUpAfterStartup() {
        if (!properties.enabled()) return;
        executor.submit(() -> {
            try {
                int recovered = store.recoverAbandonedRuns() + supplyChain.recoverAbandonedRuns();
                if (recovered > 0) {
                    log.warn("已关闭 {} 条因服务重启遗留的同步运行记录", recovered);
                }
                ensureAllWorkspaces();
                for (WorkspacePrincipal principal : workspaces.connectorWorkspaces()) {
                    boolean enabled = store.sourceStates(principal.workspaceId()).stream()
                            .anyMatch(s -> s.sourceCode().equals(ConnectorSource.LINGXING_INVENTORY.name()) && s.enabled());
                    if (!enabled) continue;
                    boolean migrated = WorkspaceContext.callAs(principal, inventory::hasMcpSnapshot);
                    LocalDateTime staleBefore = LocalDateTime.now(zone()).minusHours(6);
                    if (!migrated || !store.succeededSince(principal.workspaceId(), ConnectorSource.LINGXING_INVENTORY, staleBefore)) {
                        startDomestic(principal, migrated ? "MCP_STARTUP_CATCHUP" : "MCP_MIGRATION");
                    }
                }
                if (!LocalTime.now(zone()).isBefore(LocalTime.of(8, 0))) {
                    workspaces.connectorWorkspaces().forEach(principal -> startDue(principal, "STARTUP_CATCHUP"));
                }
            } catch (Exception exception) {
                log.error("项目内置连接器启动补跑检查失败", exception);
            }
        });
    }

    @Scheduled(cron = "${walmart.ads.data-connector.cron:0 0 8 * * *}",
            zone = "${walmart.ads.data-connector.zone:Asia/Shanghai}")
    public void scheduledSync() {
        if (!properties.enabled()) return;
        ensureAllWorkspaces();
        workspaces.connectorWorkspaces().forEach(principal -> startDue(principal, "CRON"));
    }

    /** 国内仓库存每 6 小时通过 MCP 同步，与浏览器采集任务独立。 */
    @Scheduled(cron = "0 5 0/6 * * *", zone = "${walmart.ads.data-connector.zone:Asia/Shanghai}")
    public void scheduledSixHourlyDomesticSync() {
        if (!properties.enabled()) return;
        ensureAllWorkspaces();
        LocalDateTime cutoff = LocalDateTime.now(zone()).minusHours(5).minusMinutes(50);
        for (WorkspacePrincipal principal : workspaces.connectorWorkspaces()) {
            if (!store.succeededSince(principal.workspaceId(), ConnectorSource.LINGXING_INVENTORY, cutoff)) {
                startDomestic(principal, "SIX_HOURLY_CRON");
            }
        }
    }

    @Scheduled(cron = "0 30 2 * * *", zone = "${walmart.ads.data-connector.zone:Asia/Shanghai}")
    public void cleanupAudit() {
        store.cleanupRuns(properties.resolvedLogRetentionDays());
    }

    public StartResult startManual(WorkspacePrincipal actor, Long workspaceId, String sourceName) {
        WorkspacePrincipal target = resolveTarget(actor, workspaceId);
        EnumSet<ConnectorSource> sources = parseSources(target, sourceName);
        if (sources.contains(ConnectorSource.LINGXING_WFS)) {
            throw new IllegalArgumentException("WFS 货件已改由领星 MCP 同步，请在领星 MCP 同步计划中操作");
        }
        boolean accepted = start(target, sources, "MANUAL", true);
        return new StartResult(accepted, target.workspaceId(), target.storeName(),
                sources.stream().map(Enum::name).toList(),
                accepted ? "同步任务已启动" : "该店铺已有同步任务正在运行");
    }

    public StartResult startLogin(WorkspacePrincipal actor, Long workspaceId, String systemName) {
        WorkspacePrincipal target = resolveTarget(actor, workspaceId);
        ConnectorSystem system;
        try {
            system = ConnectorSystem.valueOf(value(systemName).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("浏览器登录只支持供应链");
        }
        if (system != ConnectorSystem.SUPPLY_CHAIN) {
            throw new IllegalArgumentException("领星数据由 MCP 同步，无需登录领星浏览器页面");
        }
        if (!target.supplyChainEnabled()) throw new IllegalArgumentException("当前店铺未启用供应链同步");
        store.ensureWorkspace(target);
        return new StartResult(true, target.workspaceId(), target.storeName(), List.of(system.name()),
                "将使用当前 Chrome 检查" + system.label() + "登录状态");
    }

    public WorkspacePrincipal bridgeTarget(WorkspacePrincipal actor, Long workspaceId) {
        return resolveTarget(actor, workspaceId);
    }

    public List<WorkspaceStatus> visibleStatus(WorkspacePrincipal actor) {
        List<WorkspacePrincipal> targets = isAdmin(actor)
                ? workspaces.connectorWorkspaces()
                : List.of(resolveTarget(actor, actor.workspaceId()));
        List<WorkspaceStatus> result = new ArrayList<>();
        for (WorkspacePrincipal target : targets) {
            store.ensureWorkspace(target);
            store.expireStaleBrowserRequests(target.workspaceId(), properties.loginTimeout().toMinutes());
            List<SystemStatus> systems = Arrays.stream(ConnectorSystem.values())
                    .filter(system -> system == ConnectorSystem.SUPPLY_CHAIN && target.supplyChainEnabled())
                    .map(system -> toSystemStatus(target.workspaceId(), system))
                    .toList();
            List<DataConnectorStore.SourceState> sources = store.sourceStates(target.workspaceId()).stream()
                    .filter(DataConnectorStore.SourceState::enabled)
                    .filter(row -> !ConnectorSource.LINGXING_WFS.name().equals(row.sourceCode()))
                    .toList();
            boolean abnormal = sources.stream().anyMatch(row -> row.enabled()
                    && ("FAILED".equals(row.status()) || "LOGIN_REQUIRED".equals(row.status())
                        || "WAITING_PAGE".equals(row.status())))
                    || systems.stream().anyMatch(system -> "LOGIN_REQUIRED".equals(system.status()));
            result.add(new WorkspaceStatus(target.workspaceId(), target.storeName(), target.storeAbbreviation(),
                    syncing.contains(target.workspaceId()), abnormal, systems, sources,
                    store.recentRuns(target.workspaceId(), 20), currentBrowserBridge.status(target.workspaceId())));
        }
        return result;
    }

    private void ensureAllWorkspaces() {
        workspaces.connectorWorkspaces().forEach(store::ensureWorkspace);
    }

    private void startDue(WorkspacePrincipal principal, String triggeredBy) {
        LocalDate today = LocalDate.now(zone());
        EnumSet<ConnectorSource> due = enabledSources(principal);
        due.remove(ConnectorSource.LINGXING_INVENTORY);
        due.removeIf(source -> store.succeededToday(principal.workspaceId(), source, today));
        if (!due.isEmpty()) start(principal, due, triggeredBy, false);
    }

    private boolean start(WorkspacePrincipal principal, EnumSet<ConnectorSource> sources,
                          String triggeredBy, boolean force) {
        if (!properties.enabled()) throw new IllegalStateException("项目内置数据连接器尚未启用");
        store.ensureWorkspace(principal);
        EnumSet<ConnectorSource> selected = sources.clone();
        selected.retainAll(enabledSources(principal));
        if (!force) {
            LocalDate today = LocalDate.now(zone());
            selected.removeIf(source -> store.succeededToday(principal.workspaceId(), source, today));
        }
        if (selected.isEmpty()) return false;
        boolean accepted = false;
        if (selected.remove(ConnectorSource.LINGXING_INVENTORY)) {
            accepted = startDomestic(principal, triggeredBy);
        }
        if (!selected.isEmpty()) {
            store.requestBrowserSync(principal.workspaceId(), selected, triggeredBy);
            accepted = true;
        }
        return accepted;
    }

    private boolean startDomestic(WorkspacePrincipal principal, String triggeredBy) {
        if (store.sourceStates(principal.workspaceId()).stream().noneMatch(s -> s.enabled()
                && s.sourceCode().equals(ConnectorSource.LINGXING_INVENTORY.name()))) return false;
        if (!syncing.add(principal.workspaceId())) return false;
        try {
            executor.submit(() -> {
                try {
                    WorkspaceContext.runAs(principal,
                            () -> runSource(principal, ConnectorSource.LINGXING_INVENTORY, triggeredBy));
                } finally {
                    syncing.remove(principal.workspaceId());
                }
            });
            return true;
        } catch (RuntimeException exception) {
            syncing.remove(principal.workspaceId());
            throw exception;
        }
    }

    private void runSource(WorkspacePrincipal principal, ConnectorSource source, String triggeredBy) {
        long runId = store.startRun(principal.workspaceId(), source, triggeredBy);
        try {
            int count;
            String message;
            if (source == ConnectorSource.LINGXING_INVENTORY) {
                progress(runId, principal.workspaceId(), source, "正在通过领星 MCP 读取本地仓及店铺专属库存");
                var summary = domesticMcp.syncNow();
                count = summary.importedSkus();
                message = "同步 " + count + " 个 SKU，可用库存 " + summary.availableUnits();
            } else {
                if (!sessions.currentSupplyConfigured()) {
                    throw new ConnectorLoginRequiredException("供应链登录已失效，请点击“登录供应链”完成一次登录");
                }
                progress(runId, principal.workspaceId(), source, "正在使用加密凭证读取生产与物流数据");
                SupplyChainSyncService.SyncSummary summary = supplyChain.syncNow("PROJECT_CONNECTOR_" + triggeredBy);
                if (!"SUCCESS".equals(summary.status()) && !sessions.currentSupplyConfigured()) {
                    throw new ConnectorLoginRequiredException("供应链登录已失效，请点击“登录供应链”完成一次登录");
                }
                if (!"SUCCESS".equals(summary.status())) {
                    throw new IllegalStateException(summary.message() == null ? "供应链同步失败" : summary.message());
                }
                count = summary.orderCount() + summary.productionCount()
                        + summary.deliveryDetailCount() + summary.logisticsCount();
                message = "订单 " + summary.orderCount() + "、生产 " + summary.productionCount()
                        + "、发货 " + summary.deliveryDetailCount() + "、物流 " + summary.logisticsCount();
            }
            store.finishRun(runId, principal.workspaceId(), source, "SUCCESS", count, message);
        } catch (ConnectorLoginRequiredException exception) {
            store.finishRun(runId, principal.workspaceId(), source, "LOGIN_REQUIRED", 0,
                    rootMessage(exception));
        } catch (Exception exception) {
            store.finishRun(runId, principal.workspaceId(), source, "FAILED", 0,
                    rootMessage(exception));
            log.error("数据连接同步失败：workspace={}, source={}", principal.workspaceId(), source, exception);
        }
    }

    private void progress(long runId, long workspaceId, ConnectorSource source, String message) {
        store.updateRunProgress(runId, workspaceId, source, message);
    }

    private SystemStatus toSystemStatus(long workspaceId, ConnectorSystem system) {
        DataConnectorStore.SessionRow row = store.session(workspaceId, system);
        boolean opening = loggingIn.contains(workspaceId + ":" + system.name());
        if (row == null) return new SystemStatus(system.name(), system.label(), "LOGIN_REQUIRED",
                null, null, "尚未登录", opening);
        return new SystemStatus(system.name(), system.label(), opening ? "LOGINING" : row.status(),
                row.lastLoginAt(), row.lastValidatedAt(), row.message(), opening);
    }

    private WorkspacePrincipal resolveTarget(WorkspacePrincipal actor, Long requestedId) {
        long targetId = requestedId == null ? actor.workspaceId() : requestedId;
        if (targetId != actor.workspaceId() && !isAdmin(actor)) {
            throw new IllegalArgumentException("只能操作当前店铺的数据连接");
        }
        return workspaces.connectorWorkspaces().stream()
                .filter(principal -> principal.workspaceId() == targetId)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("目标店铺不存在或尚未启用"));
    }

    private static EnumSet<ConnectorSource> parseSources(WorkspacePrincipal principal, String sourceName) {
        if (!StringUtils.hasText(sourceName) || "ALL".equalsIgnoreCase(sourceName)) {
            return enabledSources(principal);
        }
        try {
            return EnumSet.of(ConnectorSource.valueOf(sourceName.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("未知的数据源：" + sourceName);
        }
    }

    private static EnumSet<ConnectorSource> enabledSources(WorkspacePrincipal principal) {
        EnumSet<ConnectorSource> values = EnumSet.of(ConnectorSource.LINGXING_INVENTORY);
        if (principal.supplyChainEnabled()) values.add(ConnectorSource.SUPPLY_CHAIN);
        return values;
    }

    private ZoneId zone() {
        return ZoneId.of(properties.resolvedZone());
    }

    private boolean isAdmin(WorkspacePrincipal principal) {
        return accounts.isPrimaryWorkspace(principal.workspaceId());
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.isBlank()) return current.getClass().getSimpleName();
        return message.lines().findFirst().orElse(message).trim();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    public record StartResult(boolean accepted, long workspaceId, String storeName,
                              List<String> targets, String message) {
    }

    public record SystemStatus(String system, String label, String status,
                               LocalDateTime lastLoginAt, LocalDateTime lastValidatedAt,
                               String message, boolean loginWindowOpen) {
    }

    public record WorkspaceStatus(long workspaceId, String storeName, String storeAbbreviation,
                                  boolean syncing, boolean abnormal, List<SystemStatus> systems,
                                  List<DataConnectorStore.SourceState> sources,
                                  List<DataConnectorStore.RunRow> recentRuns,
                                  CurrentBrowserBridgeService.BridgeStatus browserBridge) {
    }
}
