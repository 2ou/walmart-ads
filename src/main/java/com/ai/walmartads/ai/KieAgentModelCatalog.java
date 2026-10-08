package com.ai.walmartads.ai;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;

/**
 * KIE text models that support the Responses function-calling contract used by database Skills.
 */
public final class KieAgentModelCatalog {

    private static final List<ModelDefinition> MODELS = List.of(
            new ModelDefinition("gpt-5-6-terra", "GPT-5.6 Terra", "GPT", "/codex/v1/responses", true,
                    "均衡分析，默认推荐"),
            new ModelDefinition("gpt-5-6-sol", "GPT-5.6 Sol", "GPT", "/codex/v1/responses", false,
                    "复杂推理与深度诊断"),
            new ModelDefinition("grok-4-6", "Grok 4.6", "Grok", "/grok/v1/responses", false,
                    "快速推理与工具调用"),
            new ModelDefinition("grok-4-5", "Grok 4.5", "Grok", "/grok/v1/responses", false,
                    "稳定版工具调用模型"),
            new ModelDefinition("kimi-k3", "Kimi K3", "Kimi", "/openai/v1/responses", false,
                    "长上下文与多轮分析")
    );

    private KieAgentModelCatalog() {
    }

    public static List<ModelDefinition> models() {
        return MODELS;
    }

    public static ModelDefinition resolve(String requestedModel, String configuredDefault) {
        String candidate = StringUtils.hasText(requestedModel) ? requestedModel.trim() : configuredDefault;
        if (!StringUtils.hasText(candidate)) candidate = defaultModel().id();
        String resolvedCandidate = candidate;
        String normalized = resolvedCandidate.toLowerCase(Locale.ROOT);
        return MODELS.stream()
                .filter(model -> model.id().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Agent 不支持文本模型 " + resolvedCandidate + "，可选模型：" +
                                String.join("、", MODELS.stream().map(ModelDefinition::id).toList())));
    }

    public static ModelDefinition defaultModel() {
        return MODELS.stream().filter(ModelDefinition::recommended).findFirst().orElse(MODELS.getFirst());
    }

    public record ModelDefinition(
            String id,
            String name,
            String family,
            String endpoint,
            boolean recommended,
            String description
    ) {
    }
}
