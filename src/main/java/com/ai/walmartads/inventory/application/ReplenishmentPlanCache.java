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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    public ReplenishmentPlanCache(ReplenishmentPlanningService planningService) {
        this.planningService = planningService;
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
        ReplenishmentPlanningService.PlanningPayload calculated = planningService.plan(days);
        byWorkspaceAndDays.put(new CacheKey(WorkspaceContext.requireId(), days), calculated);
        return calculated;
    }

    public void refreshAfterSuccessfulImport(ImportFileType type) {
        if (RELEVANT_IMPORTS.contains(type)) {
            long workspaceId = WorkspaceContext.requireId();
            new ArrayList<>(byWorkspaceAndDays.keySet()).stream()
                    .filter(key -> key.workspaceId() == workspaceId)
                    .map(CacheKey::days)
                    .forEach(this::refresh);
        }
    }

    /**
     * 使缓存失效，下一次 {@link #current(int)} 访问时惰性重算。
     *
     * <p>库存来源改为领星 MCP 每小时同步后，库存数据不再经过 {@code import_file} 落账，
     * 因此不能只靠 {@link #refreshAfterSuccessfulImport} 失效，必须由领星库存快照写入方直接触发。
     */
    public void invalidate() {
        long workspaceId = WorkspaceContext.requireId();
        byWorkspaceAndDays.keySet().removeIf(key -> key.workspaceId() == workspaceId);
        refreshing.removeIf(key -> key.workspaceId() == workspaceId);
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

    private record CacheKey(long workspaceId, int days) {
    }
}
