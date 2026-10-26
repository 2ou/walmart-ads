package com.ai.walmartads.analytics.application;

import com.ai.walmartads.workspace.WorkspaceContext;
import com.ai.walmartads.workspace.WorkspaceLingXingConfigService;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Keeps the default advertising diagnosis ready after restarts instead of charging the first user. */
@Component
public class CampaignDiagnosisCacheWarmup {

    private static final Logger log = LoggerFactory.getLogger(CampaignDiagnosisCacheWarmup.class);

    private final WorkspaceLingXingConfigService workspaceConfig;
    private final CampaignStyleDiagnosisService diagnosisService;

    public CampaignDiagnosisCacheWarmup(
            WorkspaceLingXingConfigService workspaceConfig,
            CampaignStyleDiagnosisService diagnosisService
    ) {
        this.workspaceConfig = workspaceConfig;
        this.diagnosisService = diagnosisService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prewarmActiveWorkspaces() {
        try {
            for (WorkspacePrincipal principal : workspaceConfig.activeWorkspaces("ADS", "HOURLY", null)) {
                Thread.startVirtualThread(() -> WorkspaceContext.runAs(principal, () -> prewarm(principal)));
            }
        } catch (Exception exception) {
            // 轻量上下文测试或数据库迁移未完成时不应阻断应用启动。
            log.warn("Advertising diagnosis cache warmup skipped: {}", exception.getMessage());
        }
    }

    private void prewarm(WorkspacePrincipal principal) {
        try {
            diagnosisService.prewarmDefaultRange();
            log.info("Advertising diagnosis cache warmed workspaceId={}", principal.workspaceId());
        } catch (Exception exception) {
            log.warn("Advertising diagnosis cache warmup failed workspaceId={}: {}",
                    principal.workspaceId(), exception.getMessage());
        }
    }
}
