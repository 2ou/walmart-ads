package com.ai.walmartads.workspace;

import com.ai.walmartads.inventory.application.LingxingWfsMcpSyncService;
import com.ai.walmartads.inventory.application.LingxingWfsShipmentService;
import com.ai.walmartads.lingxingorder.LingXingOrderSyncService;
import com.ai.walmartads.pricemonitor.PriceMonitorService;
import com.ai.walmartads.walmartad.WalmartAdSyncService;
import com.ai.walmartads.walmartcomment.WalmartCommentSyncService;
import com.ai.walmartads.walmartlisting.WalmartListingSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkspaceManualSyncTest {
    private final WorkspaceLingXingConfigService configs = mock(WorkspaceLingXingConfigService.class);
    private final LingXingMcpCoordinator coordinator = mock(LingXingMcpCoordinator.class);
    private final WalmartListingSyncService listings = mock(WalmartListingSyncService.class);
    private final LingxingWfsMcpSyncService wfs = mock(LingxingWfsMcpSyncService.class);
    private final WorkspacePrincipal principal = new WorkspacePrincipal(7, "store", "ACTIVE", "7", "store", "S", true, 1);
    private WorkspaceSyncScheduler scheduler;

    @BeforeEach
    void setup() {
        scheduler = new WorkspaceSyncScheduler(configs, listings, mock(LingXingOrderSyncService.class),
                mock(WalmartAdSyncService.class), mock(WalmartCommentSyncService.class), mock(PriceMonitorService.class),
                mock(WorkspaceInitializationService.class), coordinator, wfs);
        WorkspaceContext.set(principal);
        when(configs.current()).thenReturn(new WorkspaceLingXingConfigService.Config(7, "https://example.com", "key", "7", 50, List.of()));
        when(coordinator.status(anyString(), anyString(), anyString()))
                .thenReturn(new LingXingMcpCoordinator.RuntimeStatus(false, null, null, null, 0));
    }

    @AfterEach
    void cleanup() { WorkspaceContext.clear(); }

    @Test
    void manualRunUsesWorkspaceLeaseAndRecordsFailure() {
        var lease = new WorkspaceLingXingConfigService.SyncLease(7, "PRODUCTS", "token", 1, System.nanoTime());
        when(configs.tryStartSync(7, "PRODUCTS", "MANUAL")).thenReturn(lease);
        when(listings.syncNow("MANUAL")).thenAnswer(invocation -> {
            org.assertj.core.api.Assertions.assertThat(WorkspaceContext.requireId()).isEqualTo(7);
            throw new IllegalStateException("test failure");
        });
        scheduler.startManualSync("PRODUCTS");
        verify(configs, timeout(3000)).finishSync(eq(lease), eq("FAILED"), eq("test failure"), eq(0L), eq(0L), eq("FAILED"), isNull());
    }

    @Test
    void successfulManualRunRecordsCounts() {
        var lease = new WorkspaceLingXingConfigService.SyncLease(7, "INVENTORY", "token", 2, System.nanoTime());
        when(configs.tryStartSync(7, "INVENTORY", "MANUAL")).thenReturn(lease);
        when(listings.syncNow("MANUAL")).thenReturn(new WalmartListingSyncService.SyncSummary(
                12, 10, 0, "SUCCESS", null, 1, LocalDateTime.now()));
        scheduler.startManualSync("INVENTORY");
        verify(configs, timeout(3000)).finishSync(eq(lease), eq("SUCCESS"), isNull(), eq(12L), eq(10L), eq("VALID"), isNull());
    }

    @Test
    void duplicateAndUnsupportedRunsAreRejected() {
        assertThatThrownBy(() -> scheduler.startManualSync("PRODUCTS")).hasMessageContaining("正在同步");
        assertThatThrownBy(() -> scheduler.startManualSync("HISTORY")).hasMessageContaining("不支持");
        verify(listings, never()).syncNow(anyString());
    }

    @Test
    void manualAdsRequiresAnAdvertiserBoundToTheWorkspace() {
        assertThatThrownBy(() -> scheduler.startManualSync("ADS")).hasMessageContaining("绑定广告账号");
        verify(configs, never()).tryStartSync(anyLong(), anyString(), anyString());
    }

    @Test
    void coolingDownDoesNotAcquireLeaseOrCallMcp() {
        when(coordinator.status(anyString(), anyString(), anyString())).thenReturn(
                new LingXingMcpCoordinator.RuntimeStatus(true, LocalDateTime.now().plusMinutes(1), null, null, 1));
        assertThatThrownBy(() -> scheduler.startManualSync("PRODUCTS")).hasMessageContaining("冷却");
        verify(configs, never()).tryStartSync(anyLong(), anyString(), anyString());
        verify(listings, never()).syncNow(anyString());
    }

    @Test
    void manualWfsUsesSameCategoryLeaseAndRecordsCargoRows() {
        String category = WorkspaceSyncScheduler.WFS_SHIPMENTS;
        var lease = new WorkspaceLingXingConfigService.SyncLease(7, category, "token", 4, System.nanoTime());
        when(configs.tryStartSync(7, category, "MANUAL")).thenReturn(lease);
        when(wfs.syncNow()).thenReturn(new LingxingWfsShipmentService.ImportSummary(3, 2, 6, 1,
                BigDecimal.ONE, LocalDateTime.now(), "LINGXING_MCP"));
        scheduler.startManualSync(category);
        verify(configs, timeout(3000)).finishSync(eq(lease), eq("SUCCESS"), contains("2 票货件"),
                eq(3L), eq(6L), eq("VALID"), isNull());
        verifyNoInteractions(listings);
    }
}
