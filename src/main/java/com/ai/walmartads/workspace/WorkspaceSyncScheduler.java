package com.ai.walmartads.workspace;

import com.ai.walmartads.lingxingorder.LingXingOrderSyncService;
import com.ai.walmartads.pricemonitor.PriceMonitorService;
import com.ai.walmartads.walmartad.WalmartAdSyncService;
import com.ai.walmartads.walmartcomment.WalmartCommentSyncService;
import com.ai.walmartads.walmartlisting.WalmartListingSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class WorkspaceSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSyncScheduler.class);
    private final WorkspaceLingXingConfigService configs;
    private final WalmartListingSyncService listings;
    private final LingXingOrderSyncService orders;
    private final WalmartAdSyncService ads;
    private final WalmartCommentSyncService comments;
    private final PriceMonitorService prices;
    private final WorkspaceInitializationService initialization;

    public WorkspaceSyncScheduler(
            WorkspaceLingXingConfigService configs,
            WalmartListingSyncService listings,
            LingXingOrderSyncService orders,
            WalmartAdSyncService ads,
            WalmartCommentSyncService comments,
            PriceMonitorService prices,
            WorkspaceInitializationService initialization
    ) {
        this.configs = configs;
        this.listings = listings;
        this.orders = orders;
        this.ads = ads;
        this.comments = comments;
        this.prices = prices;
        this.initialization = initialization;
    }

    /** One scheduler fans out into tenant-bound work; no sync runs without an explicit WorkspaceContext. */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Shanghai")
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        int minute = now.getMinute();
        Map<Long, WorkspacePrincipal> hourly = new LinkedHashMap<>();
        for (String category : new String[]{"PRODUCTS", "SALES", "ADS", "INVENTORY"}) {
            configs.activeWorkspaces(category, "HOURLY", minute)
                    .forEach(principal -> hourly.putIfAbsent(principal.workspaceId(), principal));
        }
        hourly.values().forEach(this::runHourly);

        if (now.getHour() == 8) {
            configs.activeWorkspaces("REVIEWS", "DAILY", minute).forEach(principal ->
                    runCategory(principal, "REVIEWS", () -> comments.syncNow("CRON").status()));
        }
        configs.initializingWorkspaces().forEach(initialization::startIfNeeded);
    }

    private void runHourly(WorkspacePrincipal principal) {
        WorkspaceContext.runAs(principal, () -> {
            if (isEnabled(principal.workspaceId(), "PRODUCTS") || isEnabled(principal.workspaceId(), "INVENTORY")) {
                runCategoryBound(principal, "PRODUCTS", () -> listings.syncNow("CRON").status());
                prices.monitorOnce();
            }
            if (isEnabled(principal.workspaceId(), "SALES")) {
                runCategoryBound(principal, "SALES", () -> orders.syncNow("CRON").status());
            }
            if (isEnabled(principal.workspaceId(), "ADS")) {
                runCategoryBound(principal, "ADS", () -> ads.syncNow("CRON").status());
            }
        });
    }

    private boolean isEnabled(long workspaceId, String category) {
        return configs.activeWorkspaces(category, "HOURLY", null).stream()
                .anyMatch(principal -> principal.workspaceId() == workspaceId);
    }

    private void runCategory(WorkspacePrincipal principal, String category, SyncAction action) {
        WorkspaceContext.runAs(principal, () -> runCategoryBound(principal, category, action));
    }

    private void runCategoryBound(WorkspacePrincipal principal, String category, SyncAction action) {
        configs.markSyncStarted(principal.workspaceId(), category);
        try {
            String status = action.run();
            boolean success = status == null || "SUCCESS".equalsIgnoreCase(status) || "PARTIAL".equalsIgnoreCase(status);
            configs.markSyncFinished(principal.workspaceId(), category, success, status);
        } catch (Exception exception) {
            configs.markSyncFinished(principal.workspaceId(), category, false, exception.getMessage());
            log.error("Workspace sync failed workspaceId={}, category={}: {}",
                    principal.workspaceId(), category, exception.getMessage(), exception);
        }
    }

    @FunctionalInterface
    private interface SyncAction {
        String run();
    }
}
