package com.ai.walmartads.ai;

import com.ai.walmartads.config.KieAiProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

@Service
public class AiAnalysisService {

    private static final String PROMPT_VERSION = "walmart-ads-v1";

    private final AiAnalysisProvider provider;
    private final AiAnalysisRepository repository;
    private final AiAnalysisContextFactory contextFactory;
    private final KieAiProperties properties;
    private final ObjectMapper objectMapper;

    public AiAnalysisService(
            AiAnalysisProvider provider,
            AiAnalysisRepository repository,
            AiAnalysisContextFactory contextFactory,
            KieAiProperties properties,
            ObjectMapper objectMapper
    ) {
        this.provider = provider;
        this.repository = repository;
        this.contextFactory = contextFactory;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public AiAnalysisView analyze(int days, boolean force) {
        if (!properties.configured()) {
            throw new IllegalArgumentException("Kie AI 未启用或 API Key 未配置");
        }
        int safeDays = Math.max(1, Math.min(days, 30));
        AiAnalysisContextFactory.AiContext context = contextFactory.create(safeDays);
        String fingerprint = fingerprint(properties.model(), context.metricsJson());
        Optional<AiAnalysis> existing = repository.findByDataFingerprint(fingerprint);
        if (!force && existing.filter(item -> item.getStatus() == AiAnalysisStatus.SUCCESS).isPresent()) {
            return view(existing.orElseThrow(), true);
        }

        String requestPayload = requestPayload(safeDays, context);
        AiAnalysis record = existing.orElseGet(() ->
                new AiAnalysis(fingerprint, "kie", properties.model(), requestPayload));
        if (existing.isPresent()) {
            record.restart("kie", properties.model(), requestPayload);
        }
        repository.saveAndFlush(record);

        try {
            AiAnalysisProvider.AiAnalysisResult result = provider.analyze(
                            new AiAnalysisProvider.AiAnalysisRequest(fingerprint, context.metricsJson()))
                    .orElseThrow(() -> new IllegalArgumentException("AI 分析功能当前处于关闭状态"));
            record.succeed(result.provider(), result.model(), responsePayload(result));
            return view(repository.saveAndFlush(record), false);
        } catch (RuntimeException exception) {
            record.fail(errorPayload(exception));
            repository.saveAndFlush(record);
            throw exception;
        }
    }

    public Optional<AiAnalysisView> latest() {
        return repository.findTopByStatusOrderByCreatedAtDesc(AiAnalysisStatus.SUCCESS)
                .map(item -> view(item, true));
    }

    private String requestPayload(int days, AiAnalysisContextFactory.AiContext context) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("promptVersion", PROMPT_VERSION);
        payload.put("model", properties.model());
        payload.put("periodDays", days);
        payload.put("dataAsOf", context.dataAsOf().toString());
        try {
            payload.set("metrics", objectMapper.readTree(context.metricsJson()));
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法保存 AI 请求数据", exception);
        }
    }

    private String responsePayload(AiAnalysisProvider.AiAnalysisResult result) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("requestId", result.requestId());
        payload.put("promptTokens", result.promptTokens());
        payload.put("completionTokens", result.completionTokens());
        payload.put("totalTokens", result.totalTokens());
        try {
            payload.set("analysis", objectMapper.readTree(result.analysisJson()));
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("无法保存 AI 返回数据", exception);
        }
    }

    private String errorPayload(RuntimeException exception) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("error", rootMessage(exception));
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ignored) {
            return "{\"error\":\"AI analysis failed\"}";
        }
    }

    private AiAnalysisView view(AiAnalysis item, boolean cached) {
        try {
            JsonNode payload = objectMapper.readTree(item.getResponsePayload());
            return new AiAnalysisView(
                    item.getId(),
                    item.getDataFingerprint(),
                    item.getProvider(),
                    item.getModelName(),
                    cached,
                    item.getCreatedAt(),
                    item.getCompletedAt(),
                    payload.path("promptTokens").asInt(0),
                    payload.path("completionTokens").asInt(0),
                    payload.path("totalTokens").asInt(0),
                    payload.path("analysis")
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("已保存的 AI 分析结果无法解析", exception);
        }
    }

    private static String fingerprint(String model, String metricsJson) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(
                    (PROMPT_VERSION + "|" + model + "|" + metricsJson).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 Java 环境不支持 SHA-256", exception);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record AiAnalysisView(
            Long id,
            String dataFingerprint,
            String provider,
            String model,
            boolean cached,
            LocalDateTime createdAt,
            LocalDateTime completedAt,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            JsonNode analysis
    ) {
    }
}
