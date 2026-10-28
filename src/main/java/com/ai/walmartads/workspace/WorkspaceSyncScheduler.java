package com.ai.walmartads.workspace;

import com.ai.walmartads.lingxingorder.LingXingOrderSyncService;
import com.ai.walmartads.pricemonitor.PriceMonitorService;
import com.ai.walmartads.walmartad.WalmartAdSyncService;
import com.ai.walmartads.walmartcomment.WalmartCommentSyncService;
import com.ai.walmartads.walmartlisting.LingXingClient;
import com.ai.walmartads.walmartlisting.WalmartListingSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class WorkspaceSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceSyncScheduler.class);

    /**
     * 每 6 小时同步一轮的类目。每个类目按自己的 {@code stagger_minute} 独立触发，
     * 不再「任一命中就全跑」——那会让 4 类请求在同一分钟齐发。
     *
     * <p>2026-10-27 起由 HOURLY 改为 SIX_HOURLY：领星官方限流是「每用户 1 分钟 100 次
     * MCP TOOL」，而广告单次同步就有 288 次调用，叠加其他类目必然超限，触发网关 WAF
     * 后惩罚长达数小时。降到 6 小时一轮，配合客户端 80 次/分钟的限速，才有足够余量。
     *
     * <p>{@code INVENTORY} 与 {@code PRODUCTS} 复用同一份在线商品拉取，同分钟时只跑一次。
     */
    private static final String[] SIX_HOURLY_CATEGORIES = {"PRODUCTS", "INVENTORY", "SALES"};

    /** 同步周期（小时）。0/6/12/18 点各一轮。 */
    private static final int SYNC_INTERVAL_HOURS = 6;

    /** 价格监控是纯本地计算（不调领星），独立每小时跑，不跟着商品同步降频。 */
    private static final int PRICE_MONITOR_MINUTE = 55;

    /**
     * 广告每日归因修正的触发小时（北京 06 点）。
     *
     * <p>逐小时增量只碰 T-2 一天，T-3 以前的日子会停留在首次结算值；每天在这个整点
     * 整段重拉 4 天，把滚动回填的归因变化吃进来。选 06 点是为了和商品/订单的
     * 06:10、06:25 错开，避免三条 MCP 链路压在同一分钟。
     */
    private static final int AD_REVISION_HOUR = 6;

    /** 所有可能长时间运行、需要僵尸回收的类目。 */
    private static final String[] RECYCLABLE_CATEGORIES = {"PRODUCTS", "INVENTORY", "SALES", "ADS", "REVIEWS", "HISTORY"};

    /** RUNNING 超过该时长视为上次进程已中断（正常单类目同步远小于此值）。 */
    private static final int STALE_RUNNING_MINUTES = 30;

    private final WorkspaceLingXingConfigService configs;
    private final WalmartListingSyncService listings;
    private final LingXingOrderSyncService orders;
    private final WalmartAdSyncService ads;
    private final WalmartCommentSyncService comments;
    private final PriceMonitorService prices;
    private final WorkspaceInitializationService initialization;
    private final LingXingMcpCoordinator mcpCoordinator;

    public WorkspaceSyncScheduler(
            WorkspaceLingXingConfigService configs,
            WalmartListingSyncService listings,
            LingXingOrderSyncService orders,
            WalmartAdSyncService ads,
            WalmartCommentSyncService comments,
            PriceMonitorService prices,
            WorkspaceInitializationService initialization,
            LingXingMcpCoordinator mcpCoordinator
    ) {
        this.configs = configs;
        this.listings = listings;
        this.orders = orders;
        this.ads = ads;
        this.comments = comments;
        this.prices = prices;
        this.initialization = initialization;
        this.mcpCoordinator = mcpCoordinator;
    }

    /** One scheduler fans out into tenant-bound work; no sync runs without an explicit WorkspaceContext. */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Shanghai")
    public void tick() {
        LocalDateTime now = LocalDateTime.now();
        int minute = now.getMinute();
        recycleStaleRunning();

        Set<Long> listingsDoneThisMinute = new HashSet<>();
        if (now.getHour() % SYNC_INTERVAL_HOURS == 0) {
            for (String category : SIX_HOURLY_CATEGORIES) {
                List<WorkspacePrincipal> due = configs.activeWorkspaces(category, "SIX_HOURLY", minute);
                for (WorkspacePrincipal principal : due) {
                    if ("INVENTORY".equals(category) && listingsDoneThisMinute.contains(principal.workspaceId())) {
                        continue;
                    }
                    if ("PRODUCTS".equals(category)) {
                        listingsDoneThisMinute.add(principal.workspaceId());
                    }
                    runHourly(principal, category);
                }
            }
        }

        configs.activeWorkspaces("ADS", "HOURLY", minute).forEach(principal ->
                runHourly(principal, "ADS"));

        if (minute == PRICE_MONITOR_MINUTE) {
            runPriceMonitor();
        }

        if (now.getHour() == 8) {
            configs.activeWorkspaces("REVIEWS", "DAILY", minute).forEach(principal ->
                    runCategory(principal, "REVIEWS", () -> {
                        var summary = comments.syncNow("CRON");
                        return new SyncResult(summary.status(), summary.fetchedCount(),
                                summary.insertedCount() + summary.updatedCount(), summary.errorMessage());
                    }));
        }
        configs.initializingWorkspaces().forEach(initialization::startIfNeeded);
    }

    /**
     * 按类目执行，每个类目只做自己的事。
     *
     * <p>改造前是「任一类目命中分钟 → 商品/订单/广告三个一起跑」，于是把 4 条 MCP 链路
     * 压进同一分钟；现在错峰到各自的分钟，单分钟只有一条链路。
     */
    private void runHourly(WorkspacePrincipal principal, String category) {
        WorkspaceContext.runAs(principal, () -> {
            switch (category) {
                case "PRODUCTS" -> {
                    Outcome outcome = runCategoryBound(principal, "PRODUCTS",
                            () -> {
                                var summary = listings.syncNow("CRON");
                                return new SyncResult(summary.status(), summary.fetchedCount(),
                                        summary.updatedCount(), summary.errorMessage());
                            });
                    // 在线商品同步同时刷新库存快照，所以「当前库存」不单独发请求，只跟着记账。
                    mirrorOutcome(principal, "INVENTORY", outcome);
                }
                case "INVENTORY" -> runCategoryBound(principal, "INVENTORY",
                        () -> {
                            var summary = listings.syncNow("CRON");
                            return new SyncResult(summary.status(), summary.fetchedCount(),
                                    summary.updatedCount(), summary.errorMessage());
                        });
                case "SALES" -> runCategoryBound(principal, "SALES",
                        () -> {
                            var summary = orders.syncNow("CRON");
                            return new SyncResult(summary.status(), summary.fetchedSkuCount(),
                                    summary.insertedCount(), summary.errorMessage());
                        });
                case "ADS" -> {
                    Outcome outcome = runCategoryBound(principal, "ADS", () -> {
                        // 每小时只拉「最早可能已结算」的那一天；每天 06 点那一轮改跑 4 天归因修正，
                        // 否则已结算日会被首次结算值永久冻结（领星归因是滚动回填的）。
                        boolean revisionRound = LocalDateTime.now().getHour() == AD_REVISION_HOUR;
                        var summary = revisionRound
                                ? ads.syncDailyRevision("CRON")
                                : ads.syncNow("CRON");
                        return new SyncResult(summary.status(), summary.completedToolCalls(),
                                summary.reportRows() + summary.sliceRows(), null);
                    });
                    // 广告历史回填走断点续传（基于 wm_ads_lx_ad_sync_log 的 max(window_end)），
                    // 单批限量推进；放在广告同步之后，被限流时本轮自然不推进。
                    if (outcome == Outcome.SUCCESS) {
                        ads.resumeHistoricalBackfill();
                    }
                }
                default -> log.warn("Unknown hourly sync category={}", category);
            }
        });
    }

    /**
     * 价格监控：只比对库内价格快照，不调领星，因此不受 MCP 限流影响，也不需要降频。
     * 从商品同步分支里拆出来，避免商品降到 6 小时一轮后涨价判断跟着变迟钝。
     */
    private void runPriceMonitor() {
        try {
            prices.monitorOnce();
        } catch (Exception exception) {
            log.warn("价格监控扫描失败：{}", exception.getMessage());
        }
    }

    private void runCategory(WorkspacePrincipal principal, String category, SyncAction action) {
        WorkspaceContext.runAs(principal, () -> runCategoryBound(principal, category, action));
    }

    private Outcome runCategoryBound(WorkspacePrincipal principal, String category, SyncAction action) {
        WorkspaceLingXingConfigService.Config config = configs.current();
        WorkspaceLingXingConfigService.SyncLease lease =
                configs.tryStartSync(principal.workspaceId(), category, "CRON");
        if (lease == null) {
            log.info("Workspace sync already leased workspaceId={}, category={}", principal.workspaceId(), category);
            return Outcome.DEFERRED;
        }
        LingXingMcpCoordinator.RuntimeStatus runtime =
                mcpCoordinator.status(config.baseUrl(), config.apiKey(), category);
        if (runtime.coolingDown()) {
            configs.finishSync(lease, "DEFERRED", deferredMessage(category), 0, 0,
                    "RATE_LIMITED", runtime.limitedUntil());
            return Outcome.DEFERRED;
        }
        try {
            SyncResult result = action.run();
            String status = result.status();
            boolean skipped = "SKIPPED".equalsIgnoreCase(status);
            boolean success = status == null || "SUCCESS".equalsIgnoreCase(status) || "PARTIAL".equalsIgnoreCase(status);
            runtime = mcpCoordinator.status(config.baseUrl(), config.apiKey(), category);
            if (!success && !skipped && runtime.coolingDown()) {
                // 各域 syncNow() 会把限流吞成 FAILED 摘要，异常类型到不了这里；
                // 用冷却状态回判，把「在排队」和「真坏了」区分开。
                configs.finishSync(lease, "DEFERRED", deferredMessage(category), result.fetched(),
                        result.written(), "RATE_LIMITED", runtime.limitedUntil());
                log.warn("Workspace sync deferred workspaceId={}, category={}: status={}",
                        principal.workspaceId(), category, status);
                return Outcome.DEFERRED;
            }
            if (skipped) {
                // 领星还没结算出数据：本轮不写库，也不动 last_valid_data_at，
                // 页面上显示「等待结算」而不是绿色的「已同步」。
                configs.finishSync(lease, "SKIPPED",
                        result.message() == null ? "领星尚未结算，等待下一轮自动重试" : result.message(),
                        result.fetched(), result.written(), "UNSETTLED", null);
                log.info("Workspace sync skipped unsettled workspaceId={}, category={}",
                        principal.workspaceId(), category);
                return Outcome.SKIPPED;
            }
            String integrity = success
                    ? ("PARTIAL".equalsIgnoreCase(status) ? "PARTIAL" : "VALID")
                    : integrityFailure(result.message());
            configs.finishSync(lease, success ? status : "FAILED", result.message(), result.fetched(),
                    result.written(), integrity, null);
            return success ? Outcome.SUCCESS : Outcome.FAILED;
        } catch (LingXingClient.RateLimitedException limited) {
            // 冷却中不是失败：标记 DEFERRED，保留上一次真实结果，避免页面每个整点报红。
            runtime = mcpCoordinator.status(config.baseUrl(), config.apiKey(), category);
            configs.finishSync(lease, "DEFERRED", limited.getMessage(), 0, 0,
                    "RATE_LIMITED", runtime.limitedUntil());
            log.warn("Workspace sync deferred workspaceId={}, category={}: {}",
                    principal.workspaceId(), category, limited.getMessage());
            return Outcome.DEFERRED;
        } catch (Exception exception) {
            configs.finishSync(lease, "FAILED", exception.getMessage(), 0, 0,
                    integrityFailure(exception.getMessage()), null);
            log.error("Workspace sync failed workspaceId={}, category={}: {}",
                    principal.workspaceId(), category, exception.getMessage(), exception);
            return Outcome.FAILED;
        }
    }

    /** 让从属类目（如「当前库存」）跟随主类目的真实结果，避免长期显示「尚未运行」。 */
    private void mirrorOutcome(WorkspacePrincipal principal, String category, Outcome outcome) {
        switch (outcome) {
            case SUCCESS -> configs.markSyncFinished(principal.workspaceId(), category, true, "随商品同步刷新");
            case SKIPPED -> configs.markSyncDeferred(principal.workspaceId(), category,
                    "领星尚未结算，等待下一轮自动重试");
            case DEFERRED -> configs.markSyncDeferred(principal.workspaceId(), category, deferredMessage(category));
            case FAILED -> configs.markSyncFinished(principal.workspaceId(), category, false, "随商品同步未完成");
        }
    }

    private static String deferredMessage(String category) {
        return "领星 MCP 账号正在限流冷却（" + category + "），本轮跳过；解锁后按计划自动重试";
    }

    private static String integrityFailure(String message) {
        String value = message == null ? "" : message;
        return value.contains("0 条") || value.contains("0 个") || value.contains("异常空")
                ? "EMPTY_REJECTED" : "FAILED";
    }

    private enum Outcome {
        SUCCESS, SKIPPED, FAILED, DEFERRED
    }

    /**
     * 回收「进程已死但状态还挂在 RUNNING」的同步项。
     *
     * <p>HISTORY 从 2026-10-09 卡到现在的根因就是进程重启时没走 finally：状态永远 RUNNING，
     * 而 {@code initializingWorkspaces()} 只挑 SYNCING/FAILED，于是回填再也不会被触发。
     */
    private void recycleStaleRunning() {
        for (String category : RECYCLABLE_CATEGORIES) {
            for (long workspaceId : configs.staleRunningWorkspaces(category, STALE_RUNNING_MINUTES)) {
                String message = "上次同步被中断（服务重启或超时），已重置，将重新调度";
                configs.markSyncFinished(workspaceId, category, false, message);
                log.warn("Recycled stale RUNNING sync workspaceId={}, category={}", workspaceId, category);
                if ("HISTORY".equals(category)) {
                    // 只有把初始化状态也退回 FAILED，initializingWorkspaces() 才会重新捡起回填。
                    configs.markInitialization(workspaceId, "FAILED", "历史回填上次被中断，正在重新调度");
                }
            }
        }
    }

    @FunctionalInterface
    private interface SyncAction {
        SyncResult run();
    }

    private record SyncResult(String status, long fetched, long written, String message) { }
}
