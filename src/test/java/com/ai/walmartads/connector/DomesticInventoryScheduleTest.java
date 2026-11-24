package com.ai.walmartads.connector;

import com.ai.walmartads.config.DataConnectorProperties;
import com.ai.walmartads.inventory.application.LingxingDomesticInventoryService;
import com.ai.walmartads.inventory.application.LingxingDomesticMcpSyncService;
import com.ai.walmartads.inventory.application.LingxingWfsShipmentService;
import com.ai.walmartads.supplychain.SupplyChainSyncService;
import com.ai.walmartads.workspace.WorkspaceLingXingConfigService;
import com.ai.walmartads.workspace.WorkspacePrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DomesticInventoryScheduleTest {
    private final DataConnectorStore store = mock(DataConnectorStore.class);
    private final WorkspaceLingXingConfigService workspaces = mock(WorkspaceLingXingConfigService.class);
    private final LingxingDomesticMcpSyncService domestic = mock(LingxingDomesticMcpSyncService.class);
    private final WorkspacePrincipal principal = new WorkspacePrincipal(7, "PINKSIR", "ACTIVE", "7", "PINKSIR", "PNK", true, 1);
    private DataConnectorOrchestrator scheduler;

    @BeforeEach
    void setup() {
        var properties = new DataConnectorProperties(true, "", "", "", "Asia/Shanghai", 60, 75, 10, 30);
        scheduler = new DataConnectorOrchestrator(properties, store, workspaces, mock(EmbeddedBrowserConnector.class),
                mock(DataConnectorSessionService.class), mock(LingxingDomesticInventoryService.class), domestic,
                mock(LingxingWfsShipmentService.class), mock(SupplyChainSyncService.class), mock(CurrentBrowserBridgeService.class));
        when(workspaces.connectorWorkspaces()).thenReturn(List.of(principal));
        when(store.sourceStates(7)).thenReturn(List.of(new DataConnectorStore.SourceState("LINGXING_INVENTORY", true,
                "SUCCESS", null, null, null, "", 0, "")));
        when(domestic.syncNow()).thenReturn(new LingxingDomesticInventoryService.ImportSummary(
                1, 1, 0, BigDecimal.ONE, LocalDateTime.now(), "LINGXING_MCP"));
    }

    @AfterEach
    void cleanup() { scheduler.shutdown(); }

    @Test
    void domesticCronRunsEverySixHoursAndWfsRemainsHourly() throws Exception {
        Scheduled domesticSchedule = DataConnectorOrchestrator.class.getMethod("scheduledSixHourlyDomesticSync").getAnnotation(Scheduled.class);
        Scheduled wfsSchedule = DataConnectorOrchestrator.class.getMethod("scheduledHourlyLingxingSync").getAnnotation(Scheduled.class);
        CronExpression domesticCron = CronExpression.parse(domesticSchedule.cron());
        assertThat(domesticCron.next(LocalDateTime.of(2026, 11, 6, 0, 5))).isEqualTo(LocalDateTime.of(2026, 11, 6, 6, 5));
        assertThat(domesticCron.next(LocalDateTime.of(2026, 11, 6, 18, 5))).isEqualTo(LocalDateTime.of(2026, 11, 7, 0, 5));
        assertThat(wfsSchedule.cron()).isEqualTo("0 5 * * * *");
    }

    @Test
    void hourlyAndDailyBrowserSchedulesNeverStartDomesticInventory() {
        scheduler.scheduledHourlyLingxingSync();
        verify(store).requestBrowserSync(eq(7L), eq(EnumSet.of(ConnectorSource.LINGXING_WFS)), eq("HOURLY_CRON"));
        scheduler.scheduledSync();
        verify(store).requestBrowserSync(eq(7L), eq(EnumSet.of(ConnectorSource.LINGXING_WFS, ConnectorSource.SUPPLY_CHAIN)), eq("CRON"));
        verify(domestic, never()).syncNow();
    }

    @Test
    void sixHourlyTaskUsesMcpAndSkipsARecentSuccess() {
        scheduler.scheduledSixHourlyDomesticSync();
        verify(domestic, timeout(2000)).syncNow();
        verify(store, timeout(2000)).finishRun(anyLong(), eq(7L), eq(ConnectorSource.LINGXING_INVENTORY), eq("SUCCESS"), eq(1), anyString());
        when(store.succeededSince(eq(7L), eq(ConnectorSource.LINGXING_INVENTORY), any(LocalDateTime.class))).thenReturn(true);
        scheduler.scheduledSixHourlyDomesticSync();
        verify(domestic, times(1)).syncNow();
    }
}
