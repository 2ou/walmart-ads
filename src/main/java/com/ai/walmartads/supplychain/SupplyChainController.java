package com.ai.walmartads.supplychain;

import com.ai.walmartads.shared.api.ApiResponse;
import com.ai.walmartads.connector.DataConnectorOrchestrator;
import com.ai.walmartads.workspace.WorkspaceContext;
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
    private final DataConnectorOrchestrator connectors;

    public SupplyChainController(
            SupplyChainSyncService syncService,
            SupplyChainWorkspaceService workspaceService,
            DataConnectorOrchestrator connectors
    ) {
        this.syncService = syncService;
        this.workspaceService = workspaceService;
        this.connectors = connectors;
    }

    @GetMapping("/sync/status")
    public ApiResponse<Map<String, Object>> status() {
        return ApiResponse.ok(syncService.status());
    }

    @PostMapping("/sync")
    public ApiResponse<DataConnectorOrchestrator.StartResult> sync() {
        return ApiResponse.ok(connectors.startManual(
                WorkspaceContext.requirePrincipal(), null, "SUPPLY_CHAIN"));
    }

    @GetMapping("/workspace")
    public ApiResponse<SupplyChainWorkspaceService.WorkspacePayload> workspace() {
        return ApiResponse.ok(workspaceService.load(syncService.status()));
    }
}
