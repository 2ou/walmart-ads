package com.ai.walmartads.workspace;

import com.ai.walmartads.lingxingorder.LingXingOrderSyncService;
import com.ai.walmartads.pricemonitor.PriceMonitorService;
import com.ai.walmartads.walmartad.WalmartAdSyncService;
import com.ai.walmartads.walmartcomment.WalmartCommentSyncService;
import com.ai.walmartads.walmartlisting.WalmartListingSyncService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkspaceHourlyScheduleTest {
    @Test
    void hourlyProductAndInventoryShareOneRequestAtAnyHour() {
        var configs = mock(WorkspaceLingXingConfigService.class);
        var listings = mock(WalmartListingSyncService.class);
        var coordinator = mock(LingXingMcpCoordinator.class);
        var principal = new WorkspacePrincipal(7, "store", "ACTIVE", "7", "store", "S", true, 1);
        var scheduler = new WorkspaceSyncScheduler(configs, listings, mock(LingXingOrderSyncService.class),
                mock(WalmartAdSyncService.class), mock(WalmartCommentSyncService.class), mock(PriceMonitorService.class),
                mock(WorkspaceInitializationService.class), coordinator);
        when(configs.activeWorkspaces("PRODUCTS", "HOURLY", 10)).thenReturn(List.of(principal));
        when(configs.activeWorkspaces("INVENTORY", "HOURLY", 10)).thenReturn(List.of(principal));
        when(configs.current()).thenReturn(new WorkspaceLingXingConfigService.Config(7, "https://example.com", "key", "7", 50, List.of()));
        when(configs.tryStartSync(7, "PRODUCTS", "CRON")).thenReturn(
                new WorkspaceLingXingConfigService.SyncLease(7, "PRODUCTS", "token", 1, System.nanoTime()));
        when(coordinator.status(anyString(), anyString(), anyString()))
                .thenReturn(new LingXingMcpCoordinator.RuntimeStatus(false, null, null, null, 0));
        when(listings.syncNow("CRON")).thenReturn(new WalmartListingSyncService.SyncSummary(
                12, 10, 0, "SUCCESS", null, 1, LocalDateTime.now()));

        scheduler.tickAt(LocalDateTime.of(2026, 11, 12, 7, 10));

        verify(listings, times(1)).syncNow("CRON");
        verify(configs, never()).tryStartSync(7, "INVENTORY", "CRON");
        verify(configs, never()).activeWorkspaces(anyString(), eq("SIX_HOURLY"), eq(10));
    }

    @Test
    void legacySixHourlyPlansAreStillConsideredAtTheirScheduledHour() {
        var configs = mock(WorkspaceLingXingConfigService.class);
        var scheduler = new WorkspaceSyncScheduler(configs, mock(WalmartListingSyncService.class),
                mock(LingXingOrderSyncService.class), mock(WalmartAdSyncService.class),
                mock(WalmartCommentSyncService.class), mock(PriceMonitorService.class),
                mock(WorkspaceInitializationService.class), mock(LingXingMcpCoordinator.class));
        scheduler.tickAt(LocalDateTime.of(2026, 11, 12, 6, 25));
        verify(configs).activeWorkspaces("SALES", "HOURLY", 25);
        verify(configs).activeWorkspaces("SALES", "SIX_HOURLY", 25);
    }
}
