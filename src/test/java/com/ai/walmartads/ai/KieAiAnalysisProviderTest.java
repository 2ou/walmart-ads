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
    void callsKieChatCompletionAndParsesStructuredAnalysis() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.baseUrl("https://api.kie.ai").build();
        KieAiAnalysisProvider provider = new KieAiAnalysisProvider(properties("test-key"), objectMapper, restClient);

        server.expect(requestTo("https://api.kie.ai/gemini-2.5-flash/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.stream").value(false))
                .andExpect(jsonPath("$.include_thoughts").value(false))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andRespond(withSuccess("""
                        {
                          "id": "chatcmpl-kie-1",
                          "model": "gemini-2.5-flash",
                          "choices": [{
                            "message": {
                              "role": "assistant",
                              "content": "{\\"summary\\":\\"整体稳定\\",\\"opportunities\\":[],\\"risks\\":[],\\"actions\\":[]}"
                            },
                            "finish_reason": "stop"
                          }],
                          "usage": {
                            "prompt_tokens": 120,
                            "completion_tokens": 40,
                            "total_tokens": 160
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        AiAnalysisProvider.AiAnalysisResult result = provider.analyze(
                new AiAnalysisProvider.AiAnalysisRequest("f".repeat(64), "{\"periodDays\":7}"))
                .orElseThrow();
        JsonNode analysis = objectMapper.readTree(result.analysisJson());

        assertThat(result.provider()).isEqualTo("kie");
        assertThat(result.model()).isEqualTo("gemini-2.5-flash");
        assertThat(result.requestId()).isEqualTo("chatcmpl-kie-1");
        assertThat(result.totalTokens()).isEqualTo(160);
        assertThat(analysis.path("summary").asText()).isEqualTo("整体稳定");
        server.verify();
    }

    @Test
    void refusesCallsWhenApiKeyIsMissing() {
        KieAiAnalysisProvider provider = new KieAiAnalysisProvider(
                properties(""),
                objectMapper,
                RestClient.builder().baseUrl("https://api.kie.ai").build()
        );

        assertThatThrownBy(() -> provider.analyze(
                new AiAnalysisProvider.AiAnalysisRequest("f".repeat(64), "{}")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KIE_API_KEY");
    }

    private static KieAiProperties properties(String apiKey) {
        return new KieAiProperties(
                true,
                apiKey,
                "https://api.kie.ai",
                "gemini-2.5-flash",
                10,
                120,
                25,
                20
        );
    }
}
