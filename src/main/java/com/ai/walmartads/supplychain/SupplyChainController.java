package com.ai.walmartads.supplychain;

import com.ai.walmartads.shared.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/supply-chain")
public class SupplyChainController {

    private final SupplyChainSyncService syncService;
    private final SupplyChainWorkspaceService workspaceService;

    public SupplyChainController(
            SupplyChainSyncService syncService,
            SupplyChainWorkspaceService workspaceService
    ) {
        this.syncService = syncService;
        this.workspaceService = workspaceService;
    }

    @GetMapping("/sync/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(syncService.status());
    }

    @PostMapping("/sync")
    public ApiResponse<SupplyChainSyncService.SyncSummary> sync() {
        return ApiResponse.ok(syncService.syncNow("MANUAL"));
    }

    @GetMapping("/workspace")
    public ApiResponse<SupplyChainWorkspaceService.WorkspacePayload> workspace() {
        return ApiResponse.ok(workspaceService.load(syncService.status()));
    }
}
