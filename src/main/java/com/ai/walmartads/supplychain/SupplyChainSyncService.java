package com.ai.walmartads.supplychain;

import com.ai.walmartads.config.SupplyChainProperties;
import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspaceLingXingConfigService;
import com.ai.walmartads.workspace.WorkspaceOwnershipService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class SupplyChainSyncService {

    private static final Logger log = LoggerFactory.getLogger(SupplyChainSyncService.class);
    private final SupplyChainProperties properties;
    private final SupplyChainClient client;
    private final SupplyChainRowMapper mapper;
    private final SupplyChainStore store;
    private final WorkspaceLingXingConfigService workspaceConfig;
    private final WorkspaceOwnershipService ownership;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SupplyChainSyncService(
            SupplyChainProperties properties,
            SupplyChainClient client,
            SupplyChainRowMapper mapper,
            SupplyChainStore store,
            WorkspaceLingXingConfigService workspaceConfig,
            WorkspaceOwnershipService ownership
    ) {
        this.properties = properties;
        this.client = client;
        this.mapper = mapper;
        this.store = store;
        this.workspaceConfig = workspaceConfig;
        this.ownership = ownership;
    }

    /** 每天北京时间 08:00 依次同步生产中订单、生产发货单。 */
    @Scheduled(
            cron = "${walmart.ads.supply-chain.cron:0 0 8 * * *}",
            zone = "${walmart.ads.supply-chain.zone:Asia/Shanghai}"
    )
    public void scheduledSync() {
        workspaceConfig.supplyChainWorkspaces().forEach(principal ->
                WorkspaceContext.runAs(principal, () -> syncNow("CRON")));
    }

    public SyncSummary syncNow(String triggeredBy) {
        if (!running.compareAndSet(false, true)) {
            return new SyncSummary("RUNNING", 0, 0, 0, 0,
                    "已有供应链同步任务正在执行", LocalDateTime.now());
        }
        long started = System.currentTimeMillis();
        long runId = 0;
        try {
            runId = store.startRun(triggeredBy);
            if (!properties.configured()) {
                String message = properties.enabled()
                        ? "供应链同步未配置令牌或只读账号"
                        : "供应链同步尚未启用";
                long duration = System.currentTimeMillis() - started;
                store.finishRun(runId, "SKIPPED", 0, 0, 0, duration, message);
                log.info("{}，本次任务安全跳过", message);
                return new SyncSummary("SKIPPED", 0, 0, 0, duration, message, LocalDateTime.now());
            }

            List<Map<String, Object>> rawProduction = client.fetchProductionOrders();
            List<Map<String, Object>> deliverySheets = client.fetchDeliverySheets();
            List<Map<String, Object>> rawDeliveryDetails = client.fetchDeliveryDetails(deliverySheets);
            if (rawProduction.isEmpty() && deliverySheets.isEmpty()) {
                throw new IllegalStateException("双接口同时返回空数据，已拒绝覆盖上一版快照");
            }
            if (!deliverySheets.isEmpty() && rawDeliveryDetails.isEmpty()) {
                throw new IllegalStateException("发货单存在但 SKU 明细为空，已拒绝覆盖上一版快照");
            }
            List<SupplyChainDeliveryDetail> mappedDeliveries = mapper.deliveries(rawDeliveryDetails);
            List<SupplyChainDeliveryDetail> deliveries = mappedDeliveries.stream()
                    .filter(row -> keepDelivery(row))
                    .toList();
            List<SupplyChainProductionOrder> mappedProduction = mapper.productionOrders(rawProduction, deliveries);
            List<SupplyChainProductionOrder> production = mappedProduction.stream()
                    .filter(row -> keepProduction(row))
                    .toList();
            if (!rawProduction.isEmpty() && production.isEmpty()) {
                throw new IllegalStateException("生产中订单未解析出任何 SKU，已拒绝覆盖上一版快照");
            }
            LocalDateTime snapshotAt = LocalDateTime.now();
            store.replaceSnapshot(production, deliveries, snapshotAt);
            long duration = System.currentTimeMillis() - started;
            store.finishRun(runId, "SUCCESS", production.size(), deliverySheets.size(),
                    deliveries.size(), duration, null);
            log.info("供应链双接口同步完成：生产SKU={}，发货单={}，发货SKU={}，耗时={}ms",
                    production.size(), deliverySheets.size(), deliveries.size(), duration);
            return new SyncSummary("SUCCESS", production.size(), deliverySheets.size(), deliveries.size(),
                    duration, null, snapshotAt);
        } catch (Exception exception) {
            long duration = System.currentTimeMillis() - started;
            String message = truncate(rootMessage(exception));
            if (runId > 0) {
                store.finishRun(runId, "FAILED", 0, 0, 0, duration, message);
            }
            log.error("供应链双接口同步失败：{}", message);
            return new SyncSummary("FAILED", 0, 0, 0, duration, message, LocalDateTime.now());
        } finally {
            running.set(false);
        }
    }

    private boolean keepDelivery(SupplyChainDeliveryDetail row) {
        if (ownership.belongsToCurrent(row.styleNo(), row.sku())) return true;
        ownership.quarantineRecord("SUPPLY_CHAIN_DELIVERY", row.sourceKey(), row.rawPayload(),
                row.styleNo(), row.sku());
        return false;
    }

    private boolean keepProduction(SupplyChainProductionOrder row) {
        if (ownership.belongsToCurrent(row.styleNo(), row.sku())) return true;
        ownership.quarantineRecord("SUPPLY_CHAIN_PRODUCTION", row.sourceKey(), row.rawPayload(),
                row.styleNo(), row.sku());
        return false;
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.enabled());
        result.put("configured", properties.configured());
        result.put("running", running.get());
        result.put("cron", properties.resolvedCron());
        result.put("zone", properties.resolvedZone());
        result.put("activeSource", store.hasSuccessfulSnapshot() ? "SUPPLY_CHAIN" : "LEGACY_IMPORT");
        result.put("latestRun", store.latestRun());
        return result;
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static String truncate(String message) {
        return message != null && message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    public record SyncSummary(
            String status,
            int productionCount,
            int deliverySheetCount,
            int deliveryDetailCount,
            long durationMs,
            String message,
            LocalDateTime finishedAt
    ) {
    }
}
