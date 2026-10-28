package com.ai.walmartads.workspace;

import com.ai.walmartads.common.UsBusinessDate;
import com.ai.walmartads.lingxingorder.LingXingOrderSyncService;
import com.ai.walmartads.walmartad.WalmartAdSyncService;
import com.ai.walmartads.walmartcomment.WalmartCommentSyncService;
import com.ai.walmartads.walmartlisting.LingXingClient;
import com.ai.walmartads.walmartlisting.WalmartListingSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkspaceInitializationService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceInitializationService.class);

    /**
     * 同一工作区的重试间隔，与同步周期对齐（6 小时）。
     *
     * <p>领星官方限流是「每用户 1 分钟 100 次 MCP TOOL」，超限后网关会给出数小时的
     * Retry-After 惩罚。以前失败后每 20 分钟重跑一次，每次都落在惩罚窗内、白耗配额；
     * 改为 6 小时后，重试节奏与同步一致，也不会在冷却期里反复敲门。
     */
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final WorkspaceLingXingConfigService configs;
    private final WalmartListingSyncService listings;
    private final LingXingOrderSyncService orders;
    private final WalmartAdSyncService ads;
    private final WalmartCommentSyncService comments;
    private final LingXingMcpCoordinator mcpCoordinator;

    public WorkspaceInitializationService(
            WorkspaceLingXingConfigService configs,
            WalmartListingSyncService listings,
            LingXingOrderSyncService orders,
            WalmartAdSyncService ads,
            WalmartCommentSyncService comments,
            LingXingMcpCoordinator mcpCoordinator
    ) {
        this.configs = configs;
        this.listings = listings;
        this.orders = orders;
        this.ads = ads;
        this.comments = comments;
        this.mcpCoordinator = mcpCoordinator;
    }

    public void startIfNeeded(WorkspacePrincipal principal) {
        long workspaceId = principal.workspaceId();
        // Established stores use normal incremental schedules. A service restart must never replay
        // the expensive first-run sequence merely because an old initialization attempt failed.
        if (configs.hasEstablishedData(workspaceId)) {
            configs.markInitialization(workspaceId, "READY", "已有有效业务数据，后续按同步计划增量维护");
            return;
        }
        WorkspaceLingXingConfigService.Config config = WorkspaceContext.callAs(principal, configs::current);
        LingXingMcpCoordinator.RuntimeStatus runtime = mcpCoordinator.status(config.baseUrl(), config.apiKey());
        if (runtime.coolingDown()) {
            configs.deferInitializationUntil(workspaceId, runtime.limitedUntil());
            return;
        }
        if (!running.add(workspaceId)) return;
        if (!configs.claimInitializationAttempt(workspaceId)) {
            running.remove(workspaceId);
            return;
        }
        Thread.startVirtualThread(() -> WorkspaceContext.runAs(principal, () -> initialize(principal)));
    }

    private void initialize(WorkspacePrincipal principal) {
        long id = principal.workspaceId();
        try {
            configs.markInitialization(id, "SYNCING", "正在同步商品与当前库存");
            requireSuccess(listings.syncNow("INITIALIZE").status(), "商品与库存");
            requireSuccess(orders.syncNow("INITIALIZE").status(), "当前销量");
            requireSuccess(ads.syncNow("INITIALIZE").status(), "当前广告");
            requireSuccess(comments.syncNow("INITIALIZE").status(), "当前评价");
            configs.markInitialization(id, "READY", "当前数据已可用，历史数据正在后台回填");

            configs.markSyncStarted(id, "HISTORY");
            LocalDate salesEnd = UsBusinessDate.usToday();
            requireSuccess(orders.syncWindow("BACKFILL", salesEnd.minusDays(89), salesEnd).status(), "历史销量");
            requireSuccess(comments.backfillAll().status(), "历史评价");
            // 广告历史不再在这里逐日阻塞（7/21 至今近百天，中途重启就永久卡 RUNNING），
            // 交给 WalmartAdSyncService 的断点续传：基于 sync_log 的 max(window_end) 分段推进。
            ads.resumeHistoricalBackfill();
            configs.markSyncFinished(id, "HISTORY", true, "销量/评价历史已回填；广告历史后台分段续跑");
            configs.markInitialization(id, "READY", "当前与历史数据初始化完成");
        } catch (Exception exception) {
            WorkspaceLingXingConfigService.Config config = configs.current();
            // 用 coordinator 的真实冷却状态回判：LingXingClient.anyCoolingDown() 只在
            // 无数据库兜底路径下有效，有 coordinator 时恒为 false，会把限流误判成初始化失败。
            LingXingMcpCoordinator.RuntimeStatus runtime = mcpCoordinator.status(config.baseUrl(), config.apiKey());
            if (exception instanceof LingXingClient.RateLimitedException || runtime.coolingDown()) {
                // 限流不是初始化失败：保持 SYNCING（调度器会持续捡起重试），
                // 否则页面会在每次冷却窗里显示一次「初始化需要处理」。
                // 同时清掉本次尝试时间戳，让冷却一解除就能立刻接手。
                configs.deferInitializationUntil(id, runtime.limitedUntil() == null
                        ? java.time.LocalDateTime.now().plusMinutes(30) : runtime.limitedUntil());
                configs.markSyncDeferred(id, "HISTORY", "限流冷却中，将自动重试：" + exception.getMessage());
                configs.markInitialization(id, "SYNCING", "领星 MCP 限流冷却中，将自动重试：" + exception.getMessage());
                log.warn("Workspace initialization deferred workspaceId={}: {}", id, exception.getMessage());
                return;
            }
            configs.markSyncFinished(id, "HISTORY", false, exception.getMessage());
            configs.markInitialization(id, "FAILED", exception.getMessage());
            log.error("Workspace initialization failed workspaceId={}: {}", id, exception.getMessage(), exception);
        } finally {
            running.remove(id);
        }
    }

    /**
     * 初始化各阶段的成败判定。
     *
     * <p>{@code SKIPPED} 表示「领星那一天还没结算」，不是我们这边出错：把它当失败会让
     * 初始化在每次结算滞后时都退回 FAILED，并触发 6 小时重试间隔。历史回填另有断点续传，
     * 落下的那天后续会自动补上，所以这里放行。
     */
    private static void requireSuccess(String status, String stage) {
        if (!"SUCCESS".equalsIgnoreCase(status) && !"PARTIAL".equalsIgnoreCase(status)
                && !"SKIPPED".equalsIgnoreCase(status)) {
            throw new IllegalStateException(stage + "同步失败：" + status);
        }
    }
}
