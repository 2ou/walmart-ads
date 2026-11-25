package com.ai.walmartads.inventory.application;

import com.ai.walmartads.supplychain.SupplyChainSyncedEvent;
import org.springframework.context.event.EventListener;

import com.ai.walmartads.importing.domain.ImportFileType;
import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** Keeps the last completed plan available while imports and recalculations run. */
@Service
public class ReplenishmentPlanCache {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentPlanCache.class);

    private static final Set<ImportFileType> RELEVANT_IMPORTS = EnumSet.of(
            ImportFileType.INVENTORY,
            ImportFileType.PRODUCT,
            ImportFileType.PRODUCTION_ORDER,
            ImportFileType.SHIPMENT,
            ImportFileType.SHIPMENT_LOGISTICS,
            ImportFileType.DOMESTIC_INVENTORY
    );

    private static final int MIN_DAYS = 7;
    private static final int MAX_DAYS = 180;

    private final ReplenishmentPlanningService planningService;
    private final Map<CacheKey, ReplenishmentPlanningService.PlanningPayload> byWorkspaceAndDays =
            new ConcurrentHashMap<>();
    private final Set<CacheKey> refreshing = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, AtomicLong> versionByWorkspace = new ConcurrentHashMap<>();

    public ReplenishmentPlanCache(ReplenishmentPlanningService planningService) {
        this.planningService = planningService;
    }

    public long version(long workspaceId) {
        AtomicLong counter = versionByWorkspace.get(workspaceId);
        return counter == null ? 0L : counter.get();
    }

    private void bumpVersion(long workspaceId) {
        versionByWorkspace.computeIfAbsent(workspaceId, ignored -> new AtomicLong()).incrementAndGet();
    }

    private static int clamp(int targetDays) {
        return Math.max(MIN_DAYS, Math.min(targetDays, MAX_DAYS));
    }

    public ReplenishmentPlanningService.PlanningPayload current() {
        return current(ReplenishmentPlanner.DEFAULT_HORIZON_DAYS);
    }

    public ReplenishmentPlanningService.PlanningPayload current(int targetDays) {
        int days = clamp(targetDays);
        CacheKey key = new CacheKey(WorkspaceContext.requireId(), days);
        ReplenishmentPlanningService.PlanningPayload cached = byWorkspaceAndDays.get(key);
        return cached != null ? cached : currentOrRefresh(days);
    }

    private synchronized ReplenishmentPlanningService.PlanningPayload currentOrRefresh(int days) {
        CacheKey key = new CacheKey(WorkspaceContext.requireId(), days);
        ReplenishmentPlanningService.PlanningPayload cached = byWorkspaceAndDays.get(key);
        return cached != null ? cached : refresh(days);
    }

    /** Returns the last completed plan without making the caller wait for a recalculation. */
    public ReplenishmentPlanningService.PlanningPayload peek(int targetDays) {
        int days = clamp(targetDays);
        return byWorkspaceAndDays.get(new CacheKey(WorkspaceContext.requireId(), days));
    }

    public boolean isRefreshing(int targetDays) {
        int days = clamp(targetDays);
        return refreshing.contains(new CacheKey(WorkspaceContext.requireId(), days));
    }

    /**
     * Starts at most one background calculation for the workspace and target window.
     * The workspace principal is copied because request thread-locals are not inherited by virtual threads.
     */
    public void refreshAsync(int targetDays) {
        int days = clamp(targetDays);
        WorkspacePrincipal principal = WorkspaceContext.requirePrincipal();
        CacheKey key = new CacheKey(principal.workspaceId(), days);
        if (byWorkspaceAndDays.containsKey(key) || !refreshing.add(key)) {
            return;
        }
        Thread.startVirtualThread(() -> WorkspaceContext.runAs(principal, () -> {
            try {
                refresh(days);
            } catch (RuntimeException error) {
                log.warn("Background replenishment plan refresh failed for workspace {} and {} days",
                        principal.workspaceId(), days, error);
            } finally {
                refreshing.remove(key);
            }
        }));
    }

    public synchronized ReplenishmentPlanningService.PlanningPayload refresh() {
        return refresh(ReplenishmentPlanner.DEFAULT_HORIZON_DAYS);
    }

    public synchronized ReplenishmentPlanningService.PlanningPayload refresh(int targetDays) {
        int days = clamp(targetDays);
        long workspaceId = WorkspaceContext.requireId();
        ReplenishmentPlanningService.PlanningPayload calculated = planningService.plan(days);
        byWorkspaceAndDays.put(new CacheKey(workspaceId, days), calculated);
        bumpVersion(workspaceId);
        return calculated;
    }

    public void refreshAfterSuccessfulImport(ImportFileType type) {
        if (RELEVANT_IMPORTS.contains(type)) {
            long workspaceId = WorkspaceContext.requireId();
            List<Integer> targetDaysList = new ArrayList<>();
            byWorkspaceAndDays.keySet().stream()
                    .filter(key -> key.workspaceId() == workspaceId)
                    .map(CacheKey::days)
                    .forEach(targetDaysList::add);
            if (targetDaysList.isEmpty()) {
                targetDaysList.add(ReplenishmentPlanner.DEFAULT_HORIZON_DAYS);
            }
            targetDaysList.stream().distinct().forEach(this::refresh);
        }
    }

    /**
     * 使缓存失效，并在持有有效店铺上下文时于后台异步预热，避免等到用户打开页面才开始计算。
     *
     * <p>库存来源改为领星 MCP 每小时同步后，库存数据不再经过 {@code import_file} 落账，
     * 因此不能只靠 {@link #refreshAfterSuccessfulImport} 失效，必须由领星库存快照写入方直接触发。
     */
    public void invalidate() {
        long workspaceId = WorkspaceContext.requireId();
        invalidateForWorkspace(workspaceId, WorkspaceContext.current().isPresent());
    }

    private void invalidateForWorkspace(long workspaceId, boolean prewarm) {
        List<Integer> warmDays = new ArrayList<>();
        byWorkspaceAndDays.keySet().forEach(key -> {
            if (key.workspaceId() == workspaceId) {
                warmDays.add(key.days());
            }
        });
        byWorkspaceAndDays.keySet().removeIf(key -> key.workspaceId() == workspaceId);
        refreshing.removeIf(key -> key.workspaceId() == workspaceId);
        bumpVersion(workspaceId);
        if (prewarm) {
            if (warmDays.isEmpty()) {
                refreshAsync(ReplenishmentPlanner.DEFAULT_HORIZON_DAYS);
            } else {
                warmDays.stream().distinct().forEach(this::refreshAsync);
            }
        }
    }

    @EventListener
    public void onSupplyChainSynced(SupplyChainSyncedEvent event) {
        invalidate();
    }

    @EventListener
    public void onDomesticInventorySynced(DomesticInventorySyncedEvent event) {
        invalidate();
    }

    @EventListener
    public void onTransitTimingChanged(TransitTimingChangedEvent event) {
        invalidate();
    }

    @EventListener
    public void onWfsShipmentsSynced(WfsShipmentsSyncedEvent event) {
        invalidateForWorkspace(event.workspaceId(), WorkspaceContext.current().isPresent());
    }

    private record CacheKey(long workspaceId, int days) {
    }
}
