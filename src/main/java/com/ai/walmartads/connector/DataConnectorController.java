package com.ai.walmartads.connector;

import com.ai.walmartads.config.DataConnectorProperties;
import com.ai.walmartads.shared.api.ApiResponse;
import com.ai.walmartads.workspace.WorkspaceContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/data-connectors")
public class DataConnectorController {

    private final DataConnectorOrchestrator orchestrator;
    private final DataConnectorProperties properties;
    private final CurrentBrowserBridgeService browserBridge;

    public DataConnectorController(DataConnectorOrchestrator orchestrator,
                                   DataConnectorProperties properties,
                                   CurrentBrowserBridgeService browserBridge) {
        this.orchestrator = orchestrator;
        this.properties = properties;
        this.browserBridge = browserBridge;
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", properties.enabled());
        result.put("schedule", "每日 08:00");
        result.put("zone", properties.resolvedZone());
        result.put("mode", "CURRENT_CHROME");
        result.put("chrome", "当前工作台所在 Chrome");
        result.put("workspaces", orchestrator.visibleStatus(WorkspaceContext.requirePrincipal()));
        return ApiResponse.ok(result);
    }

    @PostMapping("/browser-bridge/bootstrap")
    public ApiResponse<CurrentBrowserBridgeService.Bootstrap> bootstrap(
            @RequestBody(required = false) BridgeBootstrapRequest request) {
        Long workspaceId = request == null ? null : request.workspaceId();
        var target = orchestrator.bridgeTarget(WorkspaceContext.requirePrincipal(), workspaceId);
        return ApiResponse.ok(browserBridge.bootstrap(target));
    }

    @PostMapping("/sync")
    public ApiResponse<DataConnectorOrchestrator.StartResult> sync(@RequestBody(required = false) SyncRequest request) {
        Long workspaceId = request == null ? null : request.workspaceId();
        String source = request == null ? null : request.source();
        return ApiResponse.ok(orchestrator.startManual(WorkspaceContext.requirePrincipal(), workspaceId, source));
    }

    @PostMapping("/login")
    public ApiResponse<DataConnectorOrchestrator.StartResult> login(@RequestBody LoginRequest request) {
        if (request == null || request.system() == null || request.system().isBlank()) {
            throw new IllegalArgumentException("请选择需要登录的系统");
        }
        return ApiResponse.ok(orchestrator.startLogin(
                WorkspaceContext.requirePrincipal(), request.workspaceId(), request.system()));
    }

    public record SyncRequest(Long workspaceId, String source) {
    }

    public record LoginRequest(Long workspaceId, String system) {
    }

    public record BridgeBootstrapRequest(Long workspaceId) {
    }
}
