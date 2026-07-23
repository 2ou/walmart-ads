package com.ai.walmartads.ai;

import com.ai.walmartads.config.KieAiProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(
        prefix = "walmart.ads.ai.kie",
        name = "enabled",
        havingValue = "true"
)
public class KieAiAnalysisProvider implements AiAnalysisProvider {

    private static final String DEVELOPER_PROMPT = """
            你是沃尔玛美国站广告分析师。输入已经由本地规则引擎计算，不要重新发明统计口径。
            你的职责是结合销售、广告、SKU 和规则建议，识别跨维度机会与风险，并给出谨慎、可审计的中文建议。
            不得声称已经修改预算、出价或广告活动；不得建议在样本不足时激进操作；金额单位为美元。
            输出必须严格符合 JSON Schema，不要输出 Markdown 或额外说明。
            """;

    private static final Map<String, Object> RESPONSE_FORMAT = Map.of(
            "type", "json_schema",
            "json_schema", Map.of(
                    "name", "walmart_ads_analysis",
                    "strict", true,
                    "schema", Map.of(
                            "type", "object",
                            "additionalProperties", false,
                            "properties", Map.of(
                                    "summary", Map.of("type", "string"),
                                    "opportunities", insightArraySchema(),
                                    "risks", insightArraySchema(),
                                    "actions", actionArraySchema()
                            ),
                            "required", List.of("summary", "opportunities", "risks", "actions")
                    )
            )
    );

    private final KieAiProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public KieAiAnalysisProvider(KieAiProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, createRestClient(properties));
    }

    KieAiAnalysisProvider(KieAiProperties properties, ObjectMapper objectMapper, RestClient restClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
    }

    @Override
    public Optional<AiAnalysisResult> analyze(AiAnalysisRequest request) {
        if (!properties.configured()) {
            throw new IllegalArgumentException("Kie AI 尚未配置：请设置 KIE_API_KEY 并启用 WALMART_ADS_AI_ENABLED");
        }
        Map<String, Object> body = Map.of(
                "messages", List.of(
                        message("developer", DEVELOPER_PROMPT),
                        message("user", "请分析以下沃尔玛广告聚合数据：\n" + request.metricsJson())
                ),
                "stream", false,
                "include_thoughts", false,
                "response_format", RESPONSE_FORMAT
        );
        try {
            String responseBody = restClient.post()
                    .uri(properties.chatCompletionsPath())
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(properties.apiKey()))
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return Optional.of(parseResponse(responseBody));
        } catch (RestClientResponseException exception) {
            throw new IllegalArgumentException(
                    "Kie AI 调用失败：HTTP " + exception.getStatusCode().value() + "，" + safeMessage(exception),
                    exception
            );
        }
    }

    private AiAnalysisResult parseResponse(String responseBody) {
        try {
            JsonNode response = objectMapper.readTree(responseBody);
            String content = response.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("Kie AI 返回内容为空");
            }
            JsonNode analysis = objectMapper.readTree(stripCodeFence(content));
            JsonNode usage = response.path("usage");
            return new AiAnalysisResult(
                    "kie",
                    response.path("model").asText(properties.model()),
                    response.path("id").asText(null),
                    objectMapper.writeValueAsString(analysis),
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    usage.path("total_tokens").asInt(0)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Kie AI 返回了无法解析的 JSON", exception);
        }
    }

    private static RestClient createRestClient(KieAiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.connectTimeoutSeconds())))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(1, properties.readTimeoutSeconds())));
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    private static Map<String, Object> message(String role, String text) {
        return Map.of(
                "role", role,
                "content", List.of(Map.of("type", "text", "text", text))
        );
    }

    private static Map<String, Object> insightArraySchema() {
        return Map.of(
                "type", "array",
                "maxItems", 5,
                "items", Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "title", Map.of("type", "string"),
                                "evidence", Map.of("type", "string"),
                                "recommendation", Map.of("type", "string")
                        ),
                        "required", List.of("title", "evidence", "recommendation")
                )
        );
    }

    private static Map<String, Object> actionArraySchema() {
        return Map.of(
                "type", "array",
                "maxItems", 8,
                "items", Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "priority", Map.of("type", "integer", "minimum", 1, "maximum", 8),
                                "target", Map.of("type", "string"),
                                "action", Map.of("type", "string"),
                                "reason", Map.of("type", "string")
                        ),
                        "required", List.of("priority", "target", "action", "reason")
                )
        );
    }

    private static String stripCodeFence(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        int closingFence = trimmed.lastIndexOf("```");
        if (firstLineEnd < 0 || closingFence <= firstLineEnd) {
            return trimmed;
        }
        return trimmed.substring(firstLineEnd + 1, closingFence).trim();
    }

    private static String safeMessage(RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        if (body == null || body.isBlank()) {
            return exception.getStatusText();
        }
        return body.length() > 500 ? body.substring(0, 500) : body;
    }
}
