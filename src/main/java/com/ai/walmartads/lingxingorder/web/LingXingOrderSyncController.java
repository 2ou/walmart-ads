package com.ai.walmartads.lingxingorder.web;

import com.ai.walmartads.lingxingorder.LingXingOrderSyncService;
import com.ai.walmartads.lingxingorder.domain.LingXingOrderSyncLog;
import com.ai.walmartads.lingxingorder.repository.LingXingOrderSyncLogRepository;
import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * 领星销量/订单同步入口。定时任务与「沃尔玛在线商品同步」共用小时节奏
 * （{@code walmart.ads.lingxing.cron}，默认每小时整点）自动跑，
 * 这里供手动触发与状态查询（回填历史窗口也走这个接口）。
 */
@RestController
@RequestMapping("/api/lingxing-order")
public class LingXingOrderSyncController {

    private final LingXingOrderSyncService syncService;
    private final LingXingOrderSyncLogRepository logRepository;

    public LingXingOrderSyncController(
            LingXingOrderSyncService syncService,
            LingXingOrderSyncLogRepository logRepository
    ) {
        this.syncService = syncService;
        this.logRepository = logRepository;
    }

    /** 手动触发一次滚动窗口同步（默认近 7 天） */
    @PostMapping("/sync")
    public ApiResponse<LingXingOrderSyncService.SyncSummary> sync() {
        return ApiResponse.ok(syncService.syncNow("MANUAL"));
    }

    /**
     * 按指定窗口同步（回填历史用）。窗口不得超过 90 天 —— 领星下单时间窗口硬上限。
     */
    @PostMapping("/sync/window")
    public ApiResponse<LingXingOrderSyncService.SyncSummary> syncWindow(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end
    ) {
        return ApiResponse.ok(syncService.syncWindow("BACKFILL", start, end));
    }

    /** 最近一次同步记录 */
    @GetMapping("/sync/latest")
    public ApiResponse<Map<String, Object>> latest() {
        return logRepository.findTopByOrderByStartedAtDesc()
                .map((LingXingOrderSyncLog log) -> ApiResponse.ok(syncService.toView(log)))
                .orElseGet(() -> ApiResponse.ok("暂无同步记录", Map.of()));
    }
}
