package com.ai.walmartads.inventory.application;

import com.ai.walmartads.walmartlisting.LingXingClient;
import com.ai.walmartads.workspace.LingXingMcpCoordinator;
import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspaceLingXingConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class LingxingWfsMcpSyncService {
    private final WorkspaceLingXingConfigService configs;
    private final LingXingMcpCoordinator coordinator;
    private final ObjectMapper mapper;
    private final LingxingWfsShipmentService shipments;

    public LingxingWfsMcpSyncService(WorkspaceLingXingConfigService configs,
                                    LingXingMcpCoordinator coordinator, ObjectMapper mapper,
                                    LingxingWfsShipmentService shipments) {
        this.configs = configs;
        this.coordinator = coordinator;
        this.mapper = mapper;
        this.shipments = shipments;
    }

    public LingxingWfsShipmentService.ImportSummary syncNow() {
        var principal = WorkspaceContext.requirePrincipal();
        var config = configs.current();
        if (!principal.ready() || !config.configured() || principal.workspaceId() != config.workspaceId()
                || !principal.storeId().equals(config.storeId())) {
            throw new IllegalStateException("当前店铺未绑定有效的领星 MCP，已保留原货件数据");
        }
        LocalDateTime capturedAt = LocalDateTime.now();
        var client = new LingXingClient(config.baseUrl(), config.apiKey(), config.pageSize(),
                10, 45, mapper, List.of(), "WFS_SHIPMENTS", coordinator);
        var snapshot = new LingxingWfsMcpReader(mapper, client::callTool).read(config.storeId());
        return shipments.replaceFromMcp(snapshot, config.storeId(), capturedAt);
    }

    public boolean hasMcpSnapshot() {
        return LingxingWfsShipmentService.MCP_SOURCE_TYPE.equals(shipments.status().activeSource());
    }
}
