package com.ai.walmartads.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AdvertisingAgentControllerTest {

    @Test
    void streamsProgressAndCompletedConversationAsNdjson() throws Exception {
        AdvertisingAgentService agentService = mock(AdvertisingAgentService.class);
        AdvertisingAgentConversationService conversationService = mock(AdvertisingAgentConversationService.class);
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        AdvertisingAgentController controller = new AdvertisingAgentController(
                agentService, conversationService, objectMapper);
        AdvertisingAgentTools.Scope scope = new AdvertisingAgentTools.Scope(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 28), null, "ALL");
        AiConversationStore.ConversationRow conversation = new AiConversationStore.ConversationRow(
                7L, "广告分析", "AUTO", "gpt-5-6-terra", objectMapper.valueToTree(scope), "ACTIVE", 0,
                null, null, LocalDateTime.now(), LocalDateTime.now());
        AdvertisingAgentConversationService.ConversationDetail detail =
                new AdvertisingAgentConversationService.ConversationDetail(conversation, List.of());

        when(conversationService.send(anyLong(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            AdvertisingAgentService.ProgressListener listener = invocation.getArgument(5);
            listener.onProgress(new AdvertisingAgentService.ProgressEvent(
                    91L, "scope", "SCOPE", "SUCCESS", "锁定分析范围", "2026-09-01 至 2026-09-28",
                    objectMapper.valueToTree(scope), 0L, LocalDateTime.now()));
            return detail;
        });

        ResponseEntity<StreamingResponseBody> response = controller.sendMessageStream(
                7L,
                new AdvertisingAgentController.MessageBody(
                        "AUTO", "分析广告", scope.startDate(), scope.endDate(), null, "ALL", "gpt-5-6-terra")
        );
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        String[] lines = output.toString(java.nio.charset.StandardCharsets.UTF_8).trim().split("\\n");

        assertThat(lines).hasSize(2);
        JsonNode progress = objectMapper.readTree(lines[0]);
        JsonNode complete = objectMapper.readTree(lines[1]);
        assertThat(progress.path("type").asText()).isEqualTo("progress");
        assertThat(progress.path("progress").path("stepId").asText()).isEqualTo("scope");
        assertThat(complete.path("type").asText()).isEqualTo("complete");
        assertThat(complete.path("detail").path("conversation").path("id").asLong()).isEqualTo(7L);
    }
}
