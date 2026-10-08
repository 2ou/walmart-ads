package com.ai.walmartads.walmartcomment.web;

import com.ai.walmartads.shared.api.ApiResponse;
import com.ai.walmartads.walmartcomment.WalmartCommentQueryService;
import com.ai.walmartads.walmartcomment.WalmartCommentSyncService;
import com.ai.walmartads.walmartcomment.domain.WalmartCommentSyncLog;
import com.ai.walmartads.walmartcomment.repository.WalmartCommentSyncLogRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 口碑（Walmart 店铺评价）模块接口。
 *
 * <p>评价同步由 {@code walmart.ads.lingxing.comment-cron}（默认每日 08:30）自动跑；
 * 这里提供手动触发与全量回填，以及口碑页一次取回全部数据的聚合接口。
 */
@RestController
@RequestMapping("/api/comments")
public class WalmartCommentController {

    private final WalmartCommentQueryService queryService;
    private final WalmartCommentSyncService syncService;
    private final WalmartCommentSyncLogRepository logRepository;

    public WalmartCommentController(
            WalmartCommentQueryService queryService,
            WalmartCommentSyncService syncService,
            WalmartCommentSyncLogRepository logRepository
    ) {
        this.queryService = queryService;
        this.syncService = syncService;
        this.logRepository = logRepository;
    }

    /** 口碑页数据：概览 + 款级 + 月度趋势 + 跨款痛点 + 差评明细 + 同步状态。 */
    @GetMapping("/data")
    public ApiResponse<Map<String, Object>> data() {
        return ApiResponse.ok(queryService.buildData());
    }

    /**
     * 单款口碑详情（款卡片墙点击后的下钻抽屉）。
     *
     * <p>拆成独立端点而非塞进 {@link #data()}，是为了让首屏响应体不随款数膨胀 ——
     * 全量差评原文只在真正打开某一款时才取。
     */
    @GetMapping("/style/{parentSku}")
    public ApiResponse<Map<String, Object>> styleDetail(@PathVariable("parentSku") String parentSku) {
        return ApiResponse.ok(queryService.buildStyleDetail(parentSku));
    }

    /** 手动触发一次滚动窗口同步（默认近 7 天，与每日定时同口径）。 */
    @PostMapping("/sync")
    public ApiResponse<WalmartCommentSyncService.SyncSummary> sync() {
        return ApiResponse.ok(syncService.syncNow("MANUAL"));
    }

    /**
     * 全量历史回填。评价接口无 31 天窗口上限，一次拉完（约 10 页），幂等可重复执行。
     */
    @PostMapping("/backfill")
    public ApiResponse<WalmartCommentSyncService.SyncSummary> backfill() {
        return ApiResponse.ok(syncService.backfillAll());
    }

    /** 最近一次同步记录。 */
    @GetMapping("/sync/latest")
    public ApiResponse<Map<String, Object>> latest() {
        return logRepository.findTopByOrderByStartedAtDesc()
                .map((WalmartCommentSyncLog log) -> ApiResponse.ok(syncService.toView(log)))
                .orElseGet(() -> ApiResponse.ok("暂无同步记录", Map.of()));
    }

    /** 最近 20 次同步历史。 */
    @GetMapping("/sync/history")
    public ApiResponse<List<Map<String, Object>>> history() {
        return ApiResponse.ok(logRepository.findTop20ByOrderByStartedAtDesc().stream()
                .map(syncService::toView)
                .toList());
    }
}
