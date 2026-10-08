package com.ai.walmartads.ai;

import com.ai.walmartads.config.KieAiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdvertisingAgentServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void activatesDatabaseSkillThenOnlyExecutesItsReadOnlyTool() throws Exception {
        AiSkillService skillService = mock(AiSkillService.class);
        AdvertisingAgentTools tools = mock(AdvertisingAgentTools.class);
        KieAgentModelFactory modelFactory = mock(KieAgentModelFactory.class);
        AiAgentRunStore runStore = mock(AiAgentRunStore.class);
        ChatModel chatModel = mock(ChatModel.class);
        AiRuntimeConfig config = runtimeConfig("gpt-5-6-terra");
        AiSkillService.SkillDefinition skill = new AiSkillService.SkillDefinition(
                "manual-keyword-analysis", "手动广告关键词分析", "关键词诊断", "只读分析关键词",
                "{}", 1, List.of(AdvertisingAgentTools.KEYWORDS));

        when(skillService.enabledSkills()).thenReturn(List.of(skill));
        when(skillService.enabledSkill(skill.code())).thenReturn(Optional.of(skill));
        when(modelFactory.create("gpt-5-6-terra")).thenReturn(chatModel);
        when(tools.specification(AdvertisingAgentTools.KEYWORDS)).thenReturn(
                dev.langchain4j.agent.tool.ToolSpecification.builder()
                        .name(AdvertisingAgentTools.KEYWORDS)
                        .parameters(dev.langchain4j.model.chat.request.json.JsonObjectSchema.builder().build())
                        .build());
        when(tools.execute(eq(AdvertisingAgentTools.KEYWORDS), anyString(), any()))
                .thenReturn("{\"keywords\":[{\"keywordText\":\"dress\",\"spend\":10,\"sales\":60}]}" );
        when(runStore.create(anyString(), anyString(), anyString(), anyString())).thenReturn(42L);
        when(runStore.find(42L)).thenReturn(Optional.of(runView()));

        ToolExecutionRequest activate = ToolExecutionRequest.builder()
                .id("activate-1").name("activate_skill")
                .arguments("{\"skillCode\":\"manual-keyword-analysis\"}").build();
        ToolExecutionRequest query = ToolExecutionRequest.builder()
                .id("tool-1").name(AdvertisingAgentTools.KEYWORDS)
                .arguments("{\"limit\":20}").build();
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                response(AiMessage.from(activate), 10, 2),
                response(AiMessage.from(query), 20, 3),
                response(AiMessage.from("""
                        {"summary":"dress 关键词具备放量机会","confidence":"high",
                         "findings":[],"actions":[],"limitations":[]}
                        """), 15, 8)
        );

        AdvertisingAgentService service = new AdvertisingAgentService(
                skillService, tools, modelFactory, runStore, config, objectMapper);
        AdvertisingAgentTools.Scope scope = new AdvertisingAgentTools.Scope(
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7), "PNK0079", "SP");

        List<AdvertisingAgentService.ProgressEvent> progress = new ArrayList<>();
        AiAgentRunStore.RunView result = service.run(
                new AdvertisingAgentService.RunRequest(skill.code(), "分析关键词", scope), progress::add);

        assertThat(result.id()).isEqualTo(42L);
        verify(tools).execute(eq(AdvertisingAgentTools.KEYWORDS), eq("{\"limit\":20}"), eq(scope));
        ArgumentCaptor<String> tracePayload = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> responsePayload = ArgumentCaptor.forClass(String.class);
        verify(runStore).succeed(eq(42L), eq(skill), tracePayload.capture(), responsePayload.capture(), anyString(),
                eq(45), eq(13), eq(58));
        verify(runStore, atLeastOnce()).updateTrace(eq(42L), anyString());
        JsonNode trace = objectMapper.readTree(tracePayload.getValue());
        assertThat(trace.findValuesAsText("stepId")).contains("scope", "skill");
        assertThat(trace.findValuesAsText("kind")).contains("TOOL");
        assertThat(trace.findValues("durationMs")).anyMatch(JsonNode::isNumber);
        assertThat(trace.toString()).doesNotContain("keywordText");
        assertThat(progress).anyMatch(step -> "RUNNING".equals(step.status()));
        assertThat(progress).anyMatch(step -> "SUCCESS".equals(step.status()) && "AUDIT".equals(step.kind()));
        assertThat(objectMapper.readTree(responsePayload.getValue()).path("skill").asText())
                .isEqualTo("manual-keyword-analysis");
    }

    @Test
    void kieResponsesModelCanBeConstructedWithoutCallingNetwork() {
        KieAgentModelFactory factory = new KieAgentModelFactory(
                runtimeConfig("gpt-5-6-terra"), objectMapper);

        assertThat(factory.create("gpt-5-6-terra")).isNotNull();
        assertThat(factory.create("gpt-5-6-sol")).isNotNull();
        assertThat(factory.create("grok-4-6")).isNotNull();
        assertThat(factory.create("kimi-k3")).isNotNull();
    }

    private static ChatResponse response(AiMessage message, int input, int output) {
        return ChatResponse.builder()
                .aiMessage(message)
                .modelName("gpt-5-6-terra")
                .tokenUsage(new TokenUsage(input, output, input + output))
                .build();
    }

    private static AiRuntimeConfig runtimeConfig(String model) {
        return new AiRuntimeConfig(new KieAiProperties(
                true, "test-key", "https://api.kie.ai", model,
                10, 120, 25, 20));
    }

    private AiAgentRunStore.RunView runView() {
        return new AiAgentRunStore.RunView(
                42L, "manual-keyword-analysis", "manual-keyword-analysis", 1,
                "分析关键词", objectMapper.createObjectNode(), objectMapper.createArrayNode(),
                objectMapper.createObjectNode(), "kie", "gpt-5-6-terra", "SUCCESS",
                45, 13, 58, null, java.time.LocalDateTime.now(), java.time.LocalDateTime.now());
    }
}
