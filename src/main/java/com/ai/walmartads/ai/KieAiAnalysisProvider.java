package com.ai.walmartads.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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
public class KieAiAnalysisProvider implements AiAnalysisProvider {

    private static final String DEVELOPER_PROMPT = """
            你是沃尔玛美国站广告分析师。输入已经由本地规则引擎计算，不要重新发明统计口径。
            你的职责是结合销售、广告、SKU 和规则建议，识别跨维度机会与风险，并给出谨慎、可审计的中文建议。
            不得声称已经修改预算、出价或广告活动；不得建议在样本不足时激进操作；金额单位为美元。
            """;

    private static final String SCHEMA_HINT = """
            请严格只输出一个 JSON 对象，不要包含任何 Markdown 代码块或解释文字。结构如下：
            {
              "summary": "整体结论（中文）",
              "opportunities": [{"title":"","evidence":"","recommendation":""}],
              "risks": [{"title":"","evidence":"","recommendation":""}],
              "actions": [{"priority":1,"target":"","action":"","reason":""}]
            }
            其中 opportunities 与 risks 最多 5 条，actions 最多 8 条；priority 为 1-8 的整数。
            """;

    private final AiRuntimeConfig config;
    private final ObjectMapper objectMapper;
    private volatile RestClient restClient;
    private volatile String cachedBaseUrl;

    @Autowired
    public KieAiAnalysisProvider(AiRuntimeConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.restClient = createRestClient(config);
        this.cachedBaseUrl = config.getBaseUrl();
    }

    KieAiAnalysisProvider(AiRuntimeConfig config, ObjectMapper objectMapper, RestClient restClient) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
        this.cachedBaseUrl = config.getBaseUrl();
    }

    @Override
    public Optional<AiAnalysisResult> analyze(AiAnalysisRequest request) {
        if (!config.isConfigured()) {
            throw new IllegalArgumentException("Kie AI 尚未配置：请在前端「配置 KIE」中设置 API Key 并启用。");
        }
        ensureRestClient();
        String prompt = DEVELOPER_PROMPT + "\n\n" + SCHEMA_HINT + "\n\n请分析以下沃尔玛广告聚合数据：\n" + request.metricsJson();
        Map<String, Object> body = Map.of(
                "model", config.getModel(),
                "stream", false,
                "input", List.of(
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "input_text", "text", prompt)
                        ))
                )
        );
        try {
            String responseBody = restClient.post()
                    .uri(endpointForModel(config.getModel()))
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(config.getApiKey()))
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

    private void ensureRestClient() {
        String currentBaseUrl = config.getBaseUrl();
        if (restClient == null || !currentBaseUrl.equals(cachedBaseUrl)) {
            this.restClient = createRestClient(config);
            this.cachedBaseUrl = currentBaseUrl;
        }
    }

    /**
     * KIE 的 Responses API 端点按模型家族变化：
     *   gpt-5-6-*        -> /codex/v1/responses
     *   grok-*           -> /grok/v1/responses
     *   claude-*         -> /claude/v1/responses
     * 未知家族回退到 /codex/v1/responses（与默认模型一致）。
     */
    private static String endpointForModel(String model) {
        if (model == null) {
            return "/codex/v1/responses";
        }
        if (model.startsWith("grok")) {
            return "/grok/v1/responses";
        }
        if (model.startsWith("claude")) {
            return "/claude/v1/responses";
        }
        return "/codex/v1/responses";
    }

    private AiAnalysisResult parseResponse(String responseBody) {
        try {
            JsonNode response = objectMapper.readTree(responseBody);
            String text = extractText(response);
            JsonNode analysis = parseAnalysisJson(text);
            JsonNode usage = response.path("usage");
            return new AiAnalysisResult(
                    "kie",
                    response.path("model").asText(config.getModel()),
                    response.path("id").asText(null),
                    objectMapper.writeValueAsString(analysis),
                    usage.path("input_tokens").asInt(0),
                    usage.path("output_tokens").asInt(0),
                    usage.path("total_tokens").asInt(0),
                    response.path("credits_consumed").asInt(0)
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Kie AI 返回了无法解析的 JSON", exception);
        }
    }

    private static String extractText(JsonNode response) {
        JsonNode output = response.get("output");
        if (output != null) {
            StringBuilder sb = new StringBuilder();
            if (output.isArray()) {
                for (JsonNode item : output) {
                    collectMessageText(item, sb);
                }
            } else {
                collectMessageText(output, sb);
            }
            if (sb.length() > 0) {
                return sb.toString();
            }
        }
        // OpenAI chat-completions 兼容回退
        JsonNode choices = response.get("choices");
        if (choices != null && choices.isArray() && !choices.isEmpty()) {
            return choices.get(0).path("message").path("content").asText(null);
        }
        JsonNode text = response.get("text");
        return text != null ? text.asText() : null;
    }

    private static void collectMessageText(JsonNode item, StringBuilder sb) {
        JsonNode content = item.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode c : content) {
                JsonNode t = c.get("text");
                if (t != null) {
                    sb.append(t.asText());
                }
            }
        } else {
            JsonNode t = item.get("text");
            if (t != null) {
                sb.append(t.asText());
            }
        }
    }

    private JsonNode parseAnalysisJson(String text) throws JsonProcessingException {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Kie AI 返回内容为空");
        }
        String cleaned = stripCodeFence(text);
        try {
            return objectMapper.readTree(cleaned);
        } catch (JsonProcessingException first) {
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return objectMapper.readTree(cleaned.substring(start, end + 1));
                } catch (JsonProcessingException ignored) {
                    // fall through to original error
                }
            }
            throw first;
        }
    }

    private static RestClient createRestClient(AiRuntimeConfig config) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, config.getConnectTimeoutSeconds())))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(Math.max(1, config.getReadTimeoutSeconds())));
        return RestClient.builder()
                .baseUrl(config.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
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
