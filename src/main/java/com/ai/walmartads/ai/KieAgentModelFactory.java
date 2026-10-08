package com.ai.walmartads.ai;

import dev.langchain4j.model.chat.ChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class KieAgentModelFactory {

    private final AiRuntimeConfig config;
    private final ObjectMapper objectMapper;

    public KieAgentModelFactory(AiRuntimeConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    public ChatModel create() {
        return create(config.getModel());
    }

    public ChatModel create(String requestedModel) {
        if (!config.isConfigured()) {
            throw new IllegalArgumentException("KIE AI 未启用或 API Key 未配置");
        }
        KieAgentModelCatalog.ModelDefinition model = KieAgentModelCatalog.resolve(requestedModel, config.getModel());
        return new KieResponsesChatModel(config, objectMapper, model.id(), model.endpoint());
    }
}
