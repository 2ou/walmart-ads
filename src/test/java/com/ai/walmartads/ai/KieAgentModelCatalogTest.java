package com.ai.walmartads.ai;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KieAgentModelCatalogTest {

    @Test
    void resolvesSupportedModelsToTheirResponsesEndpoints() {
        assertThat(KieAgentModelCatalog.resolve("gpt-5-6-terra", null).endpoint())
                .isEqualTo("/codex/v1/responses");
        assertThat(KieAgentModelCatalog.resolve("grok-4-6", null).endpoint())
                .isEqualTo("/grok/v1/responses");
        assertThat(KieAgentModelCatalog.resolve("kimi-k3", null).endpoint())
                .isEqualTo("/openai/v1/responses");
    }

    @Test
    void rejectsModelsWithoutDatabaseSkillToolCallingSupport() {
        assertThatThrownBy(() -> KieAgentModelCatalog.resolve("claude-opus-4-8", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持文本模型");
    }
}
