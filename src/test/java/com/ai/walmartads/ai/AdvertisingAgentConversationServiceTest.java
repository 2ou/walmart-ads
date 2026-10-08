package com.ai.walmartads.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdvertisingAgentConversationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void sendsFollowUpWithBoundedConversationHistoryAndConversationId() {
        AiConversationStore conversationStore = mock(AiConversationStore.class);
        AiAgentRunStore runStore = mock(AiAgentRunStore.class);
        AdvertisingAgentService agentService = mock(AdvertisingAgentService.class);
        AdvertisingAgentConversationService service = new AdvertisingAgentConversationService(
                conversationStore, runStore, agentService);
        AdvertisingAgentTools.Scope scope = new AdvertisingAgentTools.Scope(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 28), "PNK0079", "SP");
        AiConversationStore.ConversationRow conversation = new AiConversationStore.ConversationRow(
                7L, "分析 PNK0079", "AUTO", "grok-4-6", objectMapper.valueToTree(scope), "ACTIVE", 2,
                "建议继续观察", LocalDateTime.now(), LocalDateTime.now(), LocalDateTime.now());
        List<AiConversationStore.MessageRow> history = List.of(
                new AiConversationStore.MessageRow(1L, 7L, "USER", "先分析当前广告", null,
                        null, "SUCCESS", null, LocalDateTime.now()),
                new AiConversationStore.MessageRow(2L, 7L, "ASSISTANT", null,
                        objectMapper.createObjectNode().put("summary", "建议继续观察"),
                        8L, "SUCCESS", null, LocalDateTime.now()));
        AiAgentRunStore.RunView run = runView();

        when(conversationStore.find(7L)).thenReturn(Optional.of(conversation));
        when(conversationStore.messages(7L, 10)).thenReturn(history);
        when(conversationStore.messages(7L, 100)).thenReturn(history);
        when(agentService.run(any())).thenReturn(run);
        when(runStore.find(anyLong())).thenReturn(Optional.of(run));

        AdvertisingAgentConversationService.ConversationDetail result = service.send(
                7L, "AUTO", "第二条建议具体指什么？", scope, "grok-4-6");

        ArgumentCaptor<AdvertisingAgentService.RunRequest> requestCaptor =
                ArgumentCaptor.forClass(AdvertisingAgentService.RunRequest.class);
        verify(agentService).run(requestCaptor.capture());
        AdvertisingAgentService.RunRequest request = requestCaptor.getValue();
        assertThat(request.conversationId()).isEqualTo(7L);
        assertThat(request.model()).isEqualTo("grok-4-6");
        assertThat(request.history()).hasSize(2);
        assertThat(request.history().get(1).role()).isEqualTo("ASSISTANT");
        verify(conversationStore).addUserMessage(7L, "第二条建议具体指什么？");
        verify(conversationStore).addAssistantMessage(7L, 42L, run.response());
        assertThat(result.conversation().id()).isEqualTo(7L);
    }

    @Test
    void linksFailedConversationMessageToThePersistedRun() {
        AiConversationStore conversationStore = mock(AiConversationStore.class);
        AiAgentRunStore runStore = mock(AiAgentRunStore.class);
        AdvertisingAgentService agentService = mock(AdvertisingAgentService.class);
        AdvertisingAgentConversationService service = new AdvertisingAgentConversationService(
                conversationStore, runStore, agentService);
        AdvertisingAgentTools.Scope scope = new AdvertisingAgentTools.Scope(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 28), null, "ALL");
        AiConversationStore.ConversationRow conversation = new AiConversationStore.ConversationRow(
                7L, "广告分析", "AUTO", "gpt-5-6-terra", objectMapper.valueToTree(scope), "ACTIVE", 0,
                null, null, LocalDateTime.now(), LocalDateTime.now());

        when(conversationStore.find(7L)).thenReturn(Optional.of(conversation));
        when(conversationStore.messages(7L, 10)).thenReturn(List.of());
        when(agentService.run(any())).thenThrow(new AdvertisingAgentService.AgentRunException(
                91L, "KIE 500", new IllegalStateException("KIE 500")));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.send(
                7L, "AUTO", "分析广告", scope, "gpt-5-6-terra"))
                .isInstanceOf(AdvertisingAgentService.AgentRunException.class);

        verify(conversationStore).addErrorMessage(7L, 91L, "KIE 500");
    }

    @Test
    void restoresLegacyFailedMessageFromConversationRunAudit() {
        AiConversationStore conversationStore = mock(AiConversationStore.class);
        AiAgentRunStore runStore = mock(AiAgentRunStore.class);
        AdvertisingAgentService agentService = mock(AdvertisingAgentService.class);
        AdvertisingAgentConversationService service = new AdvertisingAgentConversationService(
                conversationStore, runStore, agentService);
        AdvertisingAgentTools.Scope scope = new AdvertisingAgentTools.Scope(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 28), null, "ALL");
        LocalDateTime failedAt = LocalDateTime.of(2026, 9, 29, 16, 46);
        AiConversationStore.ConversationRow conversation = new AiConversationStore.ConversationRow(
                7L, "广告分析", "AUTO", "gpt-5-6-terra", objectMapper.valueToTree(scope), "ACTIVE", 2,
                "分析失败", failedAt, failedAt.minusMinutes(1), failedAt);
        AiConversationStore.MessageRow failedMessage = new AiConversationStore.MessageRow(
                18L, 7L, "ASSISTANT", "分析失败：KIE 500", null,
                null, "FAILED", "KIE 500", failedAt);
        AiAgentRunStore.RunView failedRun = new AiAgentRunStore.RunView(
                91L, "AUTO", null, null, "分析广告", objectMapper.valueToTree(scope),
                objectMapper.createArrayNode(), null, "kie", "gpt-5-6-terra", "FAILED",
                0, 0, 0, "KIE 500", failedAt.minusSeconds(5), failedAt.minusSeconds(1));

        when(conversationStore.find(7L)).thenReturn(Optional.of(conversation));
        when(conversationStore.messages(7L, 100)).thenReturn(List.of(failedMessage));
        when(runStore.findLatestFailed(7L, failedAt)).thenReturn(Optional.of(failedRun));

        AdvertisingAgentConversationService.ConversationDetail detail = service.detail(7L);

        assertThat(detail.messages()).hasSize(1);
        assertThat(detail.messages().getFirst().run().id()).isEqualTo(91L);
    }

    private AiAgentRunStore.RunView runView() {
        return new AiAgentRunStore.RunView(
                42L, "AUTO", "campaign-diagnosis", 1, "第二条建议具体指什么？",
                objectMapper.createObjectNode(), objectMapper.createArrayNode(),
                objectMapper.createObjectNode().put("summary", "建议先降低浪费花费"),
                "kie", "gpt-5-6-terra", "SUCCESS", 100, 30, 130,
                null, LocalDateTime.now(), LocalDateTime.now());
    }
}
