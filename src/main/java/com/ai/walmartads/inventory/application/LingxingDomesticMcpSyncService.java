package com.ai.walmartads.inventory.application;

import com.ai.walmartads.walmartlisting.LingXingClient;
import com.ai.walmartads.workspace.LingXingMcpCoordinator;
import com.ai.walmartads.workspace.WorkspaceLingXingConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class LingxingDomesticMcpSyncService {
    private final WorkspaceLingXingConfigService configs;
    private final LingXingMcpCoordinator coordinator;
    private final ObjectMapper mapper;
    private final LingxingDomesticInventoryService inventory;
    private final ApplicationEventPublisher events;

    public LingxingDomesticMcpSyncService(WorkspaceLingXingConfigService configs,
            LingXingMcpCoordinator coordinator, ObjectMapper mapper,
            LingxingDomesticInventoryService inventory, ApplicationEventPublisher events) {
        this.configs = configs;
        this.coordinator = coordinator;
        this.mapper = mapper;
        this.inventory = inventory;
        this.events = events;
    }

    public LingxingDomesticInventoryService.ImportSummary syncNow() {
        var config = configs.current();
        if (!config.configured()) throw new IllegalStateException("领星 MCP 未配置，无法同步国内仓库存");
        var client = new LingXingClient(config.baseUrl(), config.apiKey(), config.pageSize(),
                10, 30, mapper, List.of(), "INVENTORY", coordinator);
        var snapshot = new LingxingDomesticMcpReader(mapper, client::callTool).read(config.storeId());
        var result = inventory.replaceFromMcp(snapshot, LocalDateTime.now());
        events.publishEvent(new DomesticInventorySyncedEvent());
        return result;
    }
}
