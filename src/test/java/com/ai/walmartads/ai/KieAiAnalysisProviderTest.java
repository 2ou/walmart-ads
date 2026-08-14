package com.ai.walmartads.ai;

import com.ai.walmartads.config.KieAiProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class KieAiAnalysisProviderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void callsKieResponsesApiAndParsesStructuredAnalysis() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.baseUrl("https://api.kie.ai").build();
        KieAiAnalysisProvider provider = new KieAiAnalysisProvider(runtimeConfig("test-key", "gpt-5-6-terra"), objectMapper, restClient);

        server.expect(requestTo("https://api.kie.ai/codex/v1/responses"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("gpt-5-6-terra"))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.input[0].role").value("user"))
                .andRespond(withSuccess("""
                        {
                          "id": "resp-kie-1",
                          "model": "gpt-5-6-terra",
                          "output": {
                            "type": "message",
                            "role": "assistant",
                            "content": [
                              { "type": "output_text", "text": "{\\"summary\\":\\"整体稳定\\",\\"opportunities\\":[],\\"risks\\":[],\\"actions\\":[]}" }
                            ],
                            "status": "completed"
                          },
                          "usage": {
                            "input_tokens": 120,
                            "output_tokens": 40,
                            "total_tokens": 160
                          },
                          "credits_consumed": 48,
                          "status": "completed"
                        }
                        """, MediaType.APPLICATION_JSON));

        AiAnalysisProvider.AiAnalysisResult result = provider.analyze(
                        new AiAnalysisProvider.AiAnalysisRequest("f".repeat(64), "{\"periodDays\":7}"))
                .orElseThrow();
        JsonNode analysis = objectMapper.readTree(result.analysisJson());

        assertThat(result.provider()).isEqualTo("kie");
        assertThat(result.model()).isEqualTo("gpt-5-6-terra");
        assertThat(result.requestId()).isEqualTo("resp-kie-1");
        assertThat(result.promptTokens()).isEqualTo(120);
        assertThat(result.completionTokens()).isEqualTo(40);
        assertThat(result.totalTokens()).isEqualTo(160);
        assertThat(result.creditsConsumed()).isEqualTo(48);
        assertThat(analysis.path("summary").asText()).isEqualTo("整体稳定");
        server.verify();
    }

    @Test
    void routesGrokModelToGrokEndpoint() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.baseUrl("https://api.kie.ai").build();
        KieAiAnalysisProvider provider = new KieAiAnalysisProvider(runtimeConfig("test-key", "grok-4-5"), objectMapper, restClient);

        server.expect(requestTo("https://api.kie.ai/grok/v1/responses"))
                .andExpect(jsonPath("$.model").value("grok-4-5"))
                .andRespond(withSuccess("""
                        {
                          "output": {
                            "type": "message",
                            "content": [ { "type": "output_text", "text": "{\\"summary\\":\\"grok 视角\\"}" } ]
                          },
                          "usage": { "input_tokens": 10, "output_tokens": 20, "total_tokens": 30 },
                          "credits_consumed": 99
                        }
                        """, MediaType.APPLICATION_JSON));

        AiAnalysisProvider.AiAnalysisResult result = provider.analyze(
                        new AiAnalysisProvider.AiAnalysisRequest("f".repeat(64), "{}"))
                .orElseThrow();

        assertThat(result.model()).isEqualTo("grok-4-5");
        assertThat(result.creditsConsumed()).isEqualTo(99);
        server.verify();
    }

    @Test
    void routesClaudeModelToClaudeEndpoint() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.baseUrl("https://api.kie.ai").build();
        KieAiAnalysisProvider provider = new KieAiAnalysisProvider(runtimeConfig("test-key", "claude-opus-4-8"), objectMapper, restClient);

        server.expect(requestTo("https://api.kie.ai/claude/v1/responses"))
                .andRespond(withSuccess("""
                        {
                          "output": {
                            "type": "message",
                            "content": [ { "type": "output_text", "text": "{\\"summary\\":\\"claude 视角\\"}" } ]
                          },
                          "usage": { "input_tokens": 5, "output_tokens": 6, "total_tokens": 11 },
                          "credits_consumed": 7
                        }
                        """, MediaType.APPLICATION_JSON));

        AiAnalysisProvider.AiAnalysisResult result = provider.analyze(
                        new AiAnalysisProvider.AiAnalysisRequest("f".repeat(64), "{}"))
                .orElseThrow();

        assertThat(result.model()).isEqualTo("claude-opus-4-8");
        server.verify();
    }

    @Test
    void parsesArrayShapedOutput() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.baseUrl("https://api.kie.ai").build();
        KieAiAnalysisProvider provider = new KieAiAnalysisProvider(runtimeConfig("test-key", "gpt-5-6-terra"), objectMapper, restClient);

        server.expect(requestTo("https://api.kie.ai/codex/v1/responses"))
                .andRespond(withSuccess("""
                        {
                          "output": [
                            { "type": "message", "content": [ { "type": "output_text", "text": "{\\"summary\\":\\"数组形态\\"}" } ] }
                          ],
                          "usage": { "input_tokens": 1, "output_tokens": 2, "total_tokens": 3 }
                        }
                        """, MediaType.APPLICATION_JSON));

        AiAnalysisProvider.AiAnalysisResult result = provider.analyze(
                        new AiAnalysisProvider.AiAnalysisRequest("f".repeat(64), "{}"))
                .orElseThrow();
        JsonNode analysis = objectMapper.readTree(result.analysisJson());

        assertThat(analysis.path("summary").asText()).isEqualTo("数组形态");
        assertThat(result.totalTokens()).isEqualTo(3);
        server.verify();
    }

    @Test
    void refusesCallsWhenApiKeyIsMissing() {
        KieAiAnalysisProvider provider = new KieAiAnalysisProvider(
                runtimeConfig("", "gpt-5-6-terra"),
                objectMapper,
                RestClient.builder().baseUrl("https://api.kie.ai").build()
        );

        assertThatThrownBy(() -> provider.analyze(
                        new AiAnalysisProvider.AiAnalysisRequest("f".repeat(64), "{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("尚未配置");
    }

    private static AiRuntimeConfig runtimeConfig(String apiKey, String model) {
        KieAiProperties properties = new KieAiProperties(
                true,
                apiKey,
                "https://api.kie.ai",
                model,
                10,
                120,
                25,
                20
        );
        return new AiRuntimeConfig(properties);
    }
}
