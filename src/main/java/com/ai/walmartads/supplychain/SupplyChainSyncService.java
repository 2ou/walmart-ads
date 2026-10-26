package com.ai.walmartads.supplychain;

import com.ai.walmartads.config.SupplyChainProperties;
import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspaceOwnershipService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SupplyChainSyncService {

    private static final Logger log = LoggerFactory.getLogger(SupplyChainSyncService.class);
    /**
     * 定时同步的「新鲜度」阈值：数据超过这个时长没有成功同步过，就认为错过了本轮定时同步。
     * 浏览器凭证每次送达时都会检查一次，一旦过期就立刻补跑 —— 解决「08:00 电脑没开机，
     * cron 空转过去」导致物流/生产数据整天不更新的问题。
     */
    private static final Duration STALE_AFTER = Duration.ofHours(20);

    private final SupplyChainProperties properties;
    private final SupplyChainClient client;
    private final SupplyChainRowMapper mapper;
    private final SupplyChainStore store;
    private final WorkspaceOwnershipService ownership;
    private final ApplicationEventPublisher events;
    private final Set<Long> runningWorkspaces = ConcurrentHashMap.newKeySet();

    public SupplyChainSyncService(
            SupplyChainProperties properties,
            SupplyChainClient client,
            SupplyChainRowMapper mapper,
            SupplyChainStore store,
            WorkspaceOwnershipService ownership,
            ApplicationEventPublisher events
    ) {
        this.properties = properties;
        this.client = client;
        this.mapper = mapper;
        this.store = store;
        this.ownership = ownership;
        this.events = events;
    }

    /** 数据是否已过期：从未成功同步，或距上次成功同步已超过 {@link #STALE_AFTER}。 */
    public boolean stale() {
        LocalDateTime lastSuccess = store.lastSuccessAt();
        return lastSuccess == null || lastSuccess.isBefore(LocalDateTime.now().minus(STALE_AFTER));
    }

    public SyncSummary syncNow(String triggeredBy) {
        long workspaceId = WorkspaceContext.requireId();
        if (!runningWorkspaces.add(workspaceId)) {
            return new SyncSummary("RUNNING", 0, 0, 0, 0, 0, 0,
                    "已有供应链同步任务正在执行", LocalDateTime.now());
        }
        long started = System.currentTimeMillis();
        long runId = 0;
        try {
            runId = store.startRun(triggeredBy);
            if (!client.configured()) {
                String message = properties.enabled()
                        ? "供应链同步未配置令牌或只读账号"
                        : "供应链同步尚未启用";
                long duration = System.currentTimeMillis() - started;
                store.finishRun(runId, "SKIPPED", 0, 0, 0, 0, 0, duration, message);
                log.info("{}，本次任务安全跳过", message);
                return new SyncSummary("SKIPPED", 0, 0, 0, 0, 0, duration, message, LocalDateTime.now());
            }

            List<Map<String, Object>> rawOrders = client.fetchOrders();
            List<Map<String, Object>> rawProduction = client.fetchProductionOrders();
            List<Map<String, Object>> deliverySheets = client.fetchDeliverySheets();
            List<Map<String, Object>> rawDeliveryDetails = client.fetchDeliveryDetails(deliverySheets);
            List<Map<String, Object>> rawLogistics = client.fetchLogisticsRegisters();
            if (rawOrders.isEmpty() && rawProduction.isEmpty()
                    && deliverySheets.isEmpty() && rawLogistics.isEmpty()) {
                throw new IllegalStateException("四类只读接口同时返回空数据，已拒绝覆盖上一版快照");
            }
            if (!deliverySheets.isEmpty() && rawDeliveryDetails.isEmpty()) {
                throw new IllegalStateException("发货单存在但 SKU 明细为空，已拒绝覆盖上一版快照");
            }
            List<SupplyChainDeliveryDetail> mappedDeliveries = mapper.deliveries(rawDeliveryDetails);
            if (!rawDeliveryDetails.isEmpty() && mappedDeliveries.isEmpty()) {
                throw new IllegalStateException("发货单明细未解析出任何 SKU，已拒绝覆盖上一版快照");
            }
            List<SupplyChainDeliveryDetail> deliveries = mappedDeliveries.stream()
                    .filter(row -> keepDelivery(row))
                    .toList();
            List<SupplyChainOrder> mappedOrders = mapper.orders(rawOrders);
            List<SupplyChainOrder> orders = mappedOrders.stream()
                    .filter(row -> keepOrder(row))
                    .toList();
            List<SupplyChainProductionOrder> mappedProduction = mapper.productionOrders(rawProduction, deliveries);
            List<SupplyChainProductionOrder> production = mappedProduction.stream()
                    .filter(row -> keepProduction(row))
                    .toList();
            List<SupplyChainLogisticsRegister> mappedLogistics = mapper.logisticsRegisters(rawLogistics);
            List<SupplyChainLogisticsRegister> logistics = mappedLogistics.stream()
                    .filter(row -> keepLogistics(row))
                    .toList();
            if (!rawOrders.isEmpty() && mappedOrders.isEmpty()) {
                throw new IllegalStateException("生产订单未解析出有效记录，已拒绝覆盖上一版快照");
            }
            if (!rawProduction.isEmpty() && mappedProduction.isEmpty()) {
                throw new IllegalStateException("生产中订单未解析出任何 SKU，已拒绝覆盖上一版快照");
            }
            if (!rawLogistics.isEmpty() && mappedLogistics.isEmpty()) {
                throw new IllegalStateException("物流信息登记未解析出有效记录，已拒绝覆盖上一版快照");
            }
            LocalDateTime snapshotAt = LocalDateTime.now();
            store.replaceSnapshot(orders, production, deliveries, logistics, snapshotAt);
            events.publishEvent(new SupplyChainSyncedEvent(logistics.size()));
            long duration = System.currentTimeMillis() - started;
            store.finishRun(runId, "SUCCESS", orders.size(), production.size(), deliverySheets.size(),
                    deliveries.size(), logistics.size(), duration, null);
            log.info("供应链四类数据同步完成：生产订单={}，生产SKU={}，发货单={}，发货SKU={}，物流登记={}，耗时={}ms",
                    orders.size(), production.size(), deliverySheets.size(), deliveries.size(), logistics.size(), duration);
            return new SyncSummary("SUCCESS", orders.size(), production.size(), deliverySheets.size(),
                    deliveries.size(), logistics.size(), duration, null, snapshotAt);
        } catch (Exception exception) {
            long duration = System.currentTimeMillis() - started;
            String message = truncate(rootMessage(exception));
            if (runId > 0) {
                store.finishRun(runId, "FAILED", 0, 0, 0, 0, 0, duration, message);
            }
            log.error("供应链四类数据同步失败：{}", message);
            return new SyncSummary("FAILED", 0, 0, 0, 0, 0, duration, message, LocalDateTime.now());
        } finally {
            runningWorkspaces.remove(workspaceId);
        }
    }

    public int recoverAbandonedRuns() {
        return store.recoverAbandonedRuns();
    }

    private boolean keepDelivery(SupplyChainDeliveryDetail row) {
        if (ownership.belongsToCurrent(row.styleNo(), row.sku())) return true;
        ownership.quarantineRecord("SUPPLY_CHAIN_DELIVERY", row.sourceKey(), row.rawPayload(),
                row.styleNo(), row.sku());
        return false;
    }

    private boolean keepOrder(SupplyChainOrder row) {
        if (ownership.belongsToCurrent(row.styleNo(), null)) return true;
        ownership.quarantineRecord("SUPPLY_CHAIN_ORDER", row.sourceKey(), row.rawPayload(),
                row.styleNo(), null);
        return false;
    }

    private boolean keepProduction(SupplyChainProductionOrder row) {
        if (ownership.belongsToCurrent(row.styleNo(), row.sku())) return true;
        ownership.quarantineRecord("SUPPLY_CHAIN_PRODUCTION", row.sourceKey(), row.rawPayload(),
                row.styleNo(), row.sku());
        return false;
    }

    private boolean keepLogistics(SupplyChainLogisticsRegister row) {
        String account = normalizeAccount(row.accountNumber());
        var principal = WorkspaceContext.requirePrincipal();
        boolean belongs = !account.isEmpty() && Arrays.stream(new String[]{
                        principal.username(), principal.storeName(), principal.storeAbbreviation(), principal.storeId()
                })
                .map(SupplyChainSyncService::normalizeAccount)
                .filter(value -> !value.isEmpty())
                .anyMatch(value -> account.equals(value)
                        || (value.length() >= 4 && (account.contains(value) || value.contains(account))));
        if (belongs) return true;
        ownership.quarantineRecord("SUPPLY_CHAIN_LOGISTICS", row.sourceKey(), row.rawPayload(),
                row.accountNumber());
        return false;
    }

    private static String normalizeAccount(String value) {
        if (value == null) return "";
        return value.trim().toUpperCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.enabled());
        result.put("configured", client.configured());
        result.put("running", runningWorkspaces.contains(WorkspaceContext.requireId()));
        result.put("cron", properties.resolvedCron());
        result.put("zone", properties.resolvedZone());
        result.put("activeSource", store.hasSuccessfulSnapshot() ? "SUPPLY_CHAIN" : "NONE");
        result.put("lastSuccessAt", store.lastSuccessAt());
        result.put("stale", stale());
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
            int orderCount,
            int productionCount,
            int deliverySheetCount,
            int deliveryDetailCount,
            int logisticsCount,
            long durationMs,
            String message,
            LocalDateTime finishedAt
    ) {
    }
}
