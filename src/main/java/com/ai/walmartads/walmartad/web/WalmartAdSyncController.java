package com.ai.walmartads.walmartad.web;

import com.ai.walmartads.shared.api.ApiResponse;
import com.ai.walmartads.walmartad.WalmartAdQueryService;
import com.ai.walmartads.walmartad.WalmartAdSyncService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/walmart-ad")
public class WalmartAdSyncController {

    private final WalmartAdSyncService syncService;
    private final WalmartAdQueryService queryService;

    public WalmartAdSyncController(WalmartAdSyncService syncService, WalmartAdQueryService queryService) {
        this.syncService = syncService;
        this.queryService = queryService;
    }

    /** Manually refreshes today and the configured rolling attribution window. */
    @PostMapping("/sync")
    public ApiResponse<WalmartAdSyncService.SyncSummary> sync() {
        return ApiResponse.ok(syncService.syncNow("MANUAL"));
    }

    /** Starts/resumes the one-time exact-day history backfill in a virtual thread. */
    @PostMapping("/sync/backfill")
    public ApiResponse<WalmartAdSyncService.BackfillStart> backfill(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate
    ) {
        return ApiResponse.ok(syncService.startBackfill(startDate, endDate));
    }

    @GetMapping("/sync/latest")
    public ApiResponse<Map<String, Object>> latest() {
        return ApiResponse.ok(syncService.latestStatus());
    }

    @GetMapping("/overview")
    public ApiResponse<WalmartAdQueryService.Overview> overview(
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(defaultValue = "ALL") String adType
    ) {
        return ApiResponse.ok(queryService.overview(startDate, endDate, adType));
    }

    @GetMapping("/reports")
    public ApiResponse<WalmartAdQueryService.PagedRows> reports(
            @RequestParam String level,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(defaultValue = "ALL") String adType,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int size
    ) {
        return ApiResponse.ok(queryService.reports(level, startDate, endDate, adType, search, page, size));
    }

    @GetMapping("/slices")
    public ApiResponse<java.util.List<Map<String, Object>>> slices(
            @RequestParam String sliceType,
            @RequestParam(required = false) LocalDate startDate,
            @RequestParam(required = false) LocalDate endDate,
            @RequestParam(defaultValue = "ALL") String adType
    ) {
        return ApiResponse.ok(queryService.slices(sliceType, startDate, endDate, adType));
    }
}
