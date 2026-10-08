package com.ai.walmartads.walmartlisting.web;

import com.ai.walmartads.shared.api.ApiResponse;
import com.ai.walmartads.walmartlisting.WalmartListingSyncService;
import com.ai.walmartads.walmartlisting.domain.WalmartListingSyncLog;
import com.ai.walmartads.walmartlisting.repository.WalmartListingSyncLogRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/walmart-listing")
public class WalmartListingSyncController {

    private final WalmartListingSyncService syncService;
    private final WalmartListingSyncLogRepository logRepository;

    public WalmartListingSyncController(
            WalmartListingSyncService syncService,
            WalmartListingSyncLogRepository logRepository
    ) {
        this.syncService = syncService;
        this.logRepository = logRepository;
    }

    /** 手动触发一次同步（MCP 拉取 → 回写产品库 → 落记录） */
    @PostMapping("/sync")
    public ApiResponse<WalmartListingSyncService.SyncSummary> sync() {
        return ApiResponse.ok(syncService.syncNow("MANUAL"));
    }

    /** 最近一次同步记录 */
    @GetMapping("/sync/latest")
    public ApiResponse<Map<String, Object>> latest() {
        return logRepository.findTopByOrderByStartedAtDesc()
                .map((WalmartListingSyncLog log) -> ApiResponse.ok(syncService.toView(log)))
                .orElseGet(() -> ApiResponse.ok("暂无同步记录", Map.of()));
    }
}
