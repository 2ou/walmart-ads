package com.ai.walmartads.pricemonitor;

import com.ai.walmartads.pricemonitor.web.PriceMonitorController;
import com.ai.walmartads.shared.api.ApiResponse;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceMonitorControllerTest {

    @Test
    void acknowledgesAllPendingAbnormalChangesForStyle() {
        PriceMonitorQueryService queryService = mock(PriceMonitorQueryService.class);
        PriceMonitorService monitorService = mock(PriceMonitorService.class);
        PriceMonitorController controller = new PriceMonitorController(queryService, monitorService);
        when(monitorService.acknowledgeStyle("pnk001")).thenReturn(6);

        ApiResponse<Map<String, Object>> response = controller.ackStyle("pnk001");

        assertThat(response.success()).isTrue();
        assertThat(response.message()).isEqualTo("已处理 PNK001 的 6 条异常价格变动，记录仍保留在历史中");
        assertThat(response.data()).containsEntry("parentSku", "PNK001");
        assertThat(response.data()).containsEntry("acknowledged", 6);
        verify(monitorService).acknowledgeStyle("pnk001");
    }
}
