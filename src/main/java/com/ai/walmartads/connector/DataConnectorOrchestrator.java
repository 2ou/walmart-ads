package com.ai.walmartads.connector;

import com.ai.walmartads.config.DataConnectorProperties;
import com.ai.walmartads.inventory.application.LingxingDomesticInventoryService;
import com.ai.walmartads.inventory.application.LingxingWfsShipmentService;
import com.ai.walmartads.supplychain.SupplyChainSyncService;
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
import java.nio.file.Path;
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
    private final EmbeddedBrowserConnector browser;
    private final LingxingDomesticInventoryService inventory;
    private final LingxingWfsShipmentService wfs;
    private final SupplyChainSyncService supplyChain;
    private final ExecutorService executor = Executors.newCachedThreadPool(
            Thread.ofPlatform().daemon().name("data-connector-", 0).factory());
    private final Set<Long> syncing = ConcurrentHashMap.newKeySet();
    private final Set<String> loggingIn = ConcurrentHashMap.newKeySet();

    public DataConnectorOrchestrator(
            DataConnectorProperties properties,
            DataConnectorStore store,
            WorkspaceLingXingConfigService workspaces,
            EmbeddedBrowserConnector browser,
            LingxingDomesticInventoryService inventory,
            LingxingWfsShipmentService wfs,
            SupplyChainSyncService supplyChain
    ) {
        this.properties = properties;
        this.store = store;
        this.workspaces = workspaces;
        this.browser = browser;
        this.inventory = inventory;
        this.wfs = wfs;
        this.supplyChain = supplyChain;
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

    @Scheduled(cron = "0 30 2 * * *", zone = "${walmart.ads.data-connector.zone:Asia/Shanghai}")
    public void cleanupAudit() {
        store.cleanupRuns(properties.resolvedLogRetentionDays());
    }

    public StartResult startManual(WorkspacePrincipal actor, Long workspaceId, String sourceName) {
        WorkspacePrincipal target = resolveTarget(actor, workspaceId);
        EnumSet<ConnectorSource> sources = parseSources(target, sourceName);
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
            throw new IllegalArgumentException("登录系统仅支持 LINGXING 或 SUPPLY_CHAIN");
        }
        String key = target.workspaceId() + ":" + system.name();
        if (!loggingIn.add(key)) {
            return new StartResult(false, target.workspaceId(), target.storeName(), List.of(system.name()),
                    system.label() + "登录窗口已经打开");
        }
        store.ensureWorkspace(target);
        executor.submit(() -> {
            try {
                WorkspaceContext.runAs(target, () -> browser.login(target, system));
                EnumSet<ConnectorSource> sources = system == ConnectorSystem.LINGXING
                        ? EnumSet.of(ConnectorSource.LINGXING_INVENTORY, ConnectorSource.LINGXING_WFS)
                        : EnumSet.of(ConnectorSource.SUPPLY_CHAIN);
                start(target, sources, "LOGIN_CATCHUP", true);
            } catch (Exception exception) {
                log.warn("{}登录未完成：workspace={}, {}", system.label(), target.workspaceId(),
                        rootMessage(exception));
            } finally {
                loggingIn.remove(key);
            }
        });
        return new StartResult(true, target.workspaceId(), target.storeName(), List.of(system.name()),
                "已打开" + system.label() + "登录窗口，登录成功后会自动同步");
    }

    public List<WorkspaceStatus> visibleStatus(WorkspacePrincipal actor) {
        List<WorkspacePrincipal> targets = isAdmin(actor)
                ? workspaces.connectorWorkspaces()
                : List.of(resolveTarget(actor, actor.workspaceId()));
        List<WorkspaceStatus> result = new ArrayList<>();
        for (WorkspacePrincipal target : targets) {
            store.ensureWorkspace(target);
            List<SystemStatus> systems = Arrays.stream(ConnectorSystem.values())
                    .filter(system -> system != ConnectorSystem.SUPPLY_CHAIN || target.supplyChainEnabled())
                    .map(system -> toSystemStatus(target.workspaceId(), system))
                    .toList();
            List<DataConnectorStore.SourceState> sources = store.sourceStates(target.workspaceId()).stream()
                    .filter(DataConnectorStore.SourceState::enabled)
                    .toList();
            boolean abnormal = sources.stream().anyMatch(row -> row.enabled()
                    && ("FAILED".equals(row.status()) || "LOGIN_REQUIRED".equals(row.status())))
                    || systems.stream().anyMatch(system -> "LOGIN_REQUIRED".equals(system.status()));
            result.add(new WorkspaceStatus(target.workspaceId(), target.storeName(), target.storeAbbreviation(),
                    syncing.contains(target.workspaceId()), abnormal, systems, sources,
                    store.recentRuns(target.workspaceId(), 20)));
        }
        return result;
    }

    public Path chromeExecutable() {
        return browser.chromeExecutable();
    }

    private void ensureAllWorkspaces() {
        workspaces.connectorWorkspaces().forEach(store::ensureWorkspace);
    }

    private void startDue(WorkspacePrincipal principal, String triggeredBy) {
        LocalDate today = LocalDate.now(zone());
        EnumSet<ConnectorSource> due = enabledSources(principal);
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
        if (!syncing.add(principal.workspaceId())) return false;
        executor.submit(() -> {
            try {
                for (ConnectorSource source : selected) {
                    WorkspaceContext.runAs(principal, () -> runSource(principal, source, triggeredBy));
                }
            } finally {
                syncing.remove(principal.workspaceId());
            }
        });
        return true;
    }

    private void runSource(WorkspacePrincipal principal, ConnectorSource source, String triggeredBy) {
        long runId = store.startRun(principal.workspaceId(), source, triggeredBy);
        try {
            int count;
            String message;
            if (source == ConnectorSource.LINGXING_INVENTORY) {
                EmbeddedBrowserConnector.LingxingSnapshot snapshot = browser.readLingxing(principal, source);
                var summary = inventory.replaceFromConnector(snapshot.sourceUrl(), snapshot.response(), snapshot.capturedAt());
                count = summary.importedSkus();
                message = "同步 " + count + " 个 SKU，可用库存 " + summary.availableUnits();
            } else if (source == ConnectorSource.LINGXING_WFS) {
                EmbeddedBrowserConnector.LingxingSnapshot snapshot = browser.readLingxing(principal, source);
                var summary = wfs.replaceFromConnector(snapshot.sourceUrl(), snapshot.response(), snapshot.capturedAt());
                count = summary.importedRows();
                message = "同步 " + summary.importedShipments() + " 张货件、" + count + " 行明细";
            } else {
                browser.captureSupplyCredential(principal);
                SupplyChainSyncService.SyncSummary summary = supplyChain.syncNow("PROJECT_CONNECTOR_" + triggeredBy);
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
        EnumSet<ConnectorSource> values = EnumSet.of(
                ConnectorSource.LINGXING_INVENTORY, ConnectorSource.LINGXING_WFS);
        if (principal.supplyChainEnabled()) values.add(ConnectorSource.SUPPLY_CHAIN);
        return values;
    }

    private ZoneId zone() {
        return ZoneId.of(properties.resolvedZone());
    }

    private static boolean isAdmin(WorkspacePrincipal principal) {
        return principal.username() != null && principal.username().equalsIgnoreCase("PINKSIR");
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
                                  List<DataConnectorStore.RunRow> recentRuns) {
    }
}
