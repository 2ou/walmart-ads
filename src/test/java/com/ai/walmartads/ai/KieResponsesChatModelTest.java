package com.ai.walmartads.ai;

import com.ai.walmartads.config.KieAiProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KieResponsesChatModelTest {

    @Test
    void sendsOnlyKieDocumentedResponseFieldsAndParsesFunctionCall() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.baseUrl("https://api.kie.ai").build();
        AiRuntimeConfig config = new AiRuntimeConfig(new KieAiProperties(
                true, "test-key", "https://api.kie.ai", "gpt-5-6-terra",
                10, 120, 25, 20));
        KieResponsesChatModel model = new KieResponsesChatModel(
                config, new ObjectMapper().findAndRegisterModules(), restClient);
        ToolSpecification tool = ToolSpecification.builder()
                .name("activate_skill")
                .description("激活 Skill")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("skillCode")
                        .required("skillCode")
                        .additionalProperties(false)
                        .build())
                .build();

        server.expect(requestTo("https://api.kie.ai/codex/v1/responses"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("gpt-5-6-terra"))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.input[0].role").value("system"))
                .andExpect(jsonPath("$.input[1].role").value("user"))
                .andExpect(jsonPath("$.tools[0].type").value("function"))
                .andExpect(jsonPath("$.tools[0].name").value("activate_skill"))
                .andExpect(jsonPath("$.store").doesNotExist())
                .andExpect(jsonPath("$.max_tool_calls").doesNotExist())
                .andExpect(jsonPath("$.tools[0].strict").doesNotExist())
                .andRespond(withSuccess("""
                        {
                          "id":"resp-1",
                          "model":"gpt-5-6-terra",
                          "output":[
                            {"type":"reasoning","summary":[]},
                            {"type":"function_call","call_id":"call-1","name":"activate_skill",
                             "arguments":"{\\\"skillCode\\\":\\\"data-quality-check\\\"}"}
                          ],
                          "usage":{"input_tokens":12,"output_tokens":4,"total_tokens":16},
                          "status":"completed"
                        }
                        """, MediaType.APPLICATION_JSON));

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(SystemMessage.from("系统指令"), UserMessage.from("检查数据"))
                .toolSpecifications(tool)
                .build());

        assertThat(response.aiMessage().toolExecutionRequests()).hasSize(1);
        assertThat(response.aiMessage().toolExecutionRequests().getFirst().name()).isEqualTo("activate_skill");
        assertThat(response.tokenUsage().totalTokenCount()).isEqualTo(16);
        server.verify();
    }

    @Test
    void sendsGrokToItsResponsesEndpoint() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.baseUrl("https://api.kie.ai").build();
        AiRuntimeConfig config = new AiRuntimeConfig(new KieAiProperties(
                true, "test-key", "https://api.kie.ai", "gpt-5-6-terra",
                10, 120, 25, 20));
        KieResponsesChatModel model = new KieResponsesChatModel(
                config, new ObjectMapper().findAndRegisterModules(),
                "grok-4-6", "/grok/v1/responses", restClient);

        server.expect(requestTo("https://api.kie.ai/grok/v1/responses"))
                .andExpect(jsonPath("$.model").value("grok-4-6"))
                .andRespond(withSuccess("""
                        {
                          "id":"resp-grok",
                          "model":"grok-4-6",
                          "output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}],
                          "usage":{"input_tokens":3,"output_tokens":1,"total_tokens":4}
                        }
                        """, MediaType.APPLICATION_JSON));

        ChatResponse response = model.chat(ChatRequest.builder()
                .messages(UserMessage.from("检查数据"))
                .build());

        assertThat(response.modelName()).isEqualTo("grok-4-6");
        assertThat(response.aiMessage().text()).isEqualTo("ok");
        server.verify();
    }
}
