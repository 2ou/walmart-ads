package com.ai.walmartads.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KIE implements the OpenAI Responses conversation shape, but accepts a smaller request field set.
 * This adapter keeps LangChain4j's ChatModel/message/tool abstractions while emitting only fields
 * documented by KIE: model, stream, input, tools and reasoning.
 */
public class KieResponsesChatModel implements ChatModel {

    private final AiRuntimeConfig config;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final String model;
    private final String endpoint;

    public KieResponsesChatModel(AiRuntimeConfig config, ObjectMapper objectMapper) {
        this(config, objectMapper, resolved(config), createRestClient(config));
    }

    KieResponsesChatModel(AiRuntimeConfig config, ObjectMapper objectMapper, RestClient restClient) {
        this(config, objectMapper, resolved(config), restClient);
    }

    public KieResponsesChatModel(
            AiRuntimeConfig config,
            ObjectMapper objectMapper,
            String model,
            String endpoint
    ) {
        this(config, objectMapper, definition(model, endpoint), createRestClient(config));
    }

    KieResponsesChatModel(
            AiRuntimeConfig config,
            ObjectMapper objectMapper,
            String model,
            String endpoint,
            RestClient restClient
    ) {
        this(config, objectMapper, definition(model, endpoint), restClient);
    }

    private KieResponsesChatModel(
            AiRuntimeConfig config,
            ObjectMapper objectMapper,
            KieAgentModelCatalog.ModelDefinition definition,
            RestClient restClient
    ) {
        this.config = config;
        this.objectMapper = objectMapper;
        this.restClient = restClient;
        this.model = definition.id();
        this.endpoint = definition.endpoint();
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("stream", false);
        payload.put("input", inputs(request.messages()));
        List<Map<String, Object>> toolDefinitions = tools(request.toolSpecifications());
        if (!toolDefinitions.isEmpty()) {
            payload.put("tools", toolDefinitions);
        }
        payload.put("reasoning", Map.of("effort", "medium"));

        try {
            String body = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> headers.setBearerAuth(config.getApiKey()))
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            return parseResponse(body);
        } catch (RestClientResponseException exception) {
            throw new IllegalArgumentException(
                    "KIE Agent 调用失败：HTTP " + exception.getStatusCode().value() + "，" + safeMessage(exception),
                    exception);
        }
    }

    private List<Map<String, Object>> inputs(List<ChatMessage> messages) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage system) {
                result.add(textMessage("system", "input_text", system.text()));
            } else if (message instanceof UserMessage user) {
                result.add(textMessage("user", "input_text", user.singleText()));
            } else if (message instanceof AiMessage ai) {
                if (StringUtils.hasText(ai.text())) {
                    result.add(textMessage("assistant", "output_text", ai.text()));
                }
                for (ToolExecutionRequest toolRequest : ai.toolExecutionRequests()) {
                    Map<String, Object> call = new LinkedHashMap<>();
                    call.put("type", "function_call");
                    call.put("call_id", toolRequest.id());
                    call.put("name", toolRequest.name());
                    call.put("arguments", toolRequest.arguments());
                    result.add(call);
                }
            } else if (message instanceof ToolExecutionResultMessage toolResult) {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("type", "function_call_output");
                output.put("call_id", toolResult.id());
                output.put("output", toolResult.text());
                result.add(output);
            } else {
                throw new IllegalArgumentException("KIE Agent 不支持的消息类型: " + message.getClass().getName());
            }
        }
        return result;
    }

    private static Map<String, Object> textMessage(String role, String contentType, String text) {
        return Map.of(
                "role", role,
                "content", List.of(Map.of("type", contentType, "text", text))
        );
    }

    private static List<Map<String, Object>> tools(List<ToolSpecification> specifications) {
        if (specifications == null || specifications.isEmpty()) {
            return List.of();
        }
        return specifications.stream().map(specification -> {
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("name", specification.name());
            if (StringUtils.hasText(specification.description())) {
                tool.put("description", specification.description());
            }
            tool.put("parameters", specification.parameters() == null
                    ? Map.of("type", "object", "properties", Map.of())
                    : JsonSchemaElementUtils.toMap(specification.parameters(), false));
            return tool;
        }).toList();
    }

    private ChatResponse parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode output = root.path("output");
            List<ToolExecutionRequest> toolCalls = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            if (output.isArray()) {
                for (JsonNode item : output) {
                    if ("function_call".equals(item.path("type").asText())) {
                        String callId = item.path("call_id").asText(item.path("id").asText());
                        toolCalls.add(ToolExecutionRequest.builder()
                                .id(callId)
                                .name(item.path("name").asText())
                                .arguments(item.path("arguments").asText("{}"))
                                .build());
                    } else {
                        appendText(item, text);
                    }
                }
            } else {
                appendText(output, text);
            }
            JsonNode usage = root.path("usage");
            return ChatResponse.builder()
                    .id(root.path("id").asText(null))
                    .modelName(root.path("model").asText(model))
                    .tokenUsage(new TokenUsage(
                            usage.path("input_tokens").asInt(0),
                            usage.path("output_tokens").asInt(0),
                            usage.path("total_tokens").asInt(0)))
                    .aiMessage(AiMessage.from(text.toString(), toolCalls))
                    .build();
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("KIE Agent 返回了无法解析的 JSON", exception);
        }
    }

    private static void appendText(JsonNode item, StringBuilder target) {
        JsonNode content = item.path("content");
        if (content.isArray()) {
            for (JsonNode part : content) {
                if (part.hasNonNull("text")) target.append(part.path("text").asText());
            }
        } else if (item.hasNonNull("text")) {
            target.append(item.path("text").asText());
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

    private static KieAgentModelCatalog.ModelDefinition resolved(AiRuntimeConfig config) {
        return KieAgentModelCatalog.resolve(config.getModel(), config.getModel());
    }

    private static KieAgentModelCatalog.ModelDefinition definition(String model, String endpoint) {
        KieAgentModelCatalog.ModelDefinition resolved = KieAgentModelCatalog.resolve(model, model);
        if (!resolved.endpoint().equals(endpoint)) {
            throw new IllegalArgumentException("模型 " + model + " 的 KIE 端点不匹配");
        }
        return resolved;
    }

    private static String safeMessage(RestClientResponseException exception) {
        String body = exception.getResponseBodyAsString();
        if (!StringUtils.hasText(body)) return exception.getStatusText();
        return body.length() > 500 ? body.substring(0, 500) : body;
    }
}
