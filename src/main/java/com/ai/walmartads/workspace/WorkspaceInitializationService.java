package com.ai.walmartads.workspace;

import com.ai.walmartads.common.UsBusinessDate;
import com.ai.walmartads.config.LingXingProperties;
import com.ai.walmartads.lingxingorder.LingXingOrderSyncService;
import com.ai.walmartads.walmartad.WalmartAdSyncService;
import com.ai.walmartads.walmartcomment.WalmartCommentSyncService;
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
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    private final WorkspaceLingXingConfigService configs;
    private final LingXingProperties defaults;
    private final WalmartListingSyncService listings;
    private final LingXingOrderSyncService orders;
    private final WalmartAdSyncService ads;
    private final WalmartCommentSyncService comments;

    public WorkspaceInitializationService(
            WorkspaceLingXingConfigService configs,
            LingXingProperties defaults,
            WalmartListingSyncService listings,
            LingXingOrderSyncService orders,
            WalmartAdSyncService ads,
            WalmartCommentSyncService comments
    ) {
        this.configs = configs;
        this.defaults = defaults;
        this.listings = listings;
        this.orders = orders;
        this.ads = ads;
        this.comments = comments;
    }

    public void startIfNeeded(WorkspacePrincipal principal) {
        if (!running.add(principal.workspaceId())) return;
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
            LocalDate adStart = defaults.resolvedAdBackfillStart();
            for (LocalDate date = adStart; !date.isAfter(LocalDate.now()); date = date.plusDays(1)) {
                requireSuccess(ads.syncWindow("BACKFILL", date, date).status(), "历史广告 " + date);
            }
            configs.markSyncFinished(id, "HISTORY", true, "可读取范围内的历史数据已回填");
            configs.markInitialization(id, "READY", "当前与历史数据初始化完成");
        } catch (Exception exception) {
            configs.markSyncFinished(id, "HISTORY", false, exception.getMessage());
            configs.markInitialization(id, "FAILED", exception.getMessage());
            log.error("Workspace initialization failed workspaceId={}: {}", id, exception.getMessage(), exception);
        } finally {
            running.remove(id);
        }
    }

    private static void requireSuccess(String status, String stage) {
        if (!"SUCCESS".equalsIgnoreCase(status) && !"PARTIAL".equalsIgnoreCase(status)) {
            throw new IllegalStateException(stage + "同步失败：" + status);
        }
    }
}
