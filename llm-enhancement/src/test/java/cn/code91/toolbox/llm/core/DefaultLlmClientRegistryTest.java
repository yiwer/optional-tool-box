package cn.code91.toolbox.llm.core;

import cn.code91.facility.result.Result;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DefaultLlmClientRegistry} 单测（brief 装配矩阵行的非 Spring 部分）：
 * 空 registry 引导消息、未知模型引导消息、primary 三级解析各态。
 */
class DefaultLlmClientRegistryTest {

    private static final LlmClient STUB = new LlmClient() {
        @Override
        public Result<ChatResponse, LlmError> chat(ChatRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Result<T, LlmError> chatStructured(ChatRequest request, Class<T> targetType) {
            throw new UnsupportedOperationException();
        }
    };

    @Test
    void emptyRegistryGetThrowsWithBootstrapGuidance() {
        DefaultLlmClientRegistry registry = new DefaultLlmClientRegistry(Map.of(), null);

        assertThatThrownBy(() -> registry.get("deepseek"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deepseek")
                .hasMessageContaining("toolbox.llm.models");
    }

    @Test
    void emptyRegistryPrimaryThrowsWithBootstrapGuidance() {
        DefaultLlmClientRegistry registry = new DefaultLlmClientRegistry(Map.of(), null);

        assertThatThrownBy(registry::primary)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolbox.llm.models");
    }

    @Test
    void unknownModelNameThrowsWithConfiguredModelsListed() {
        DefaultLlmClientRegistry registry = new DefaultLlmClientRegistry(Map.of("deepseek", STUB), null);

        assertThatThrownBy(() -> registry.get("qwen"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qwen")
                .hasMessageContaining("deepseek");
    }

    @Test
    void singleModelIsPrimaryWithoutExplicitConfig() {
        DefaultLlmClientRegistry registry = new DefaultLlmClientRegistry(Map.of("deepseek", STUB), null);

        assertThat(registry.primary()).isSameAs(STUB);
    }

    @Test
    void explicitPrimaryWinsOverUniqueModel() {
        LlmClient other = new LlmClient() {
            @Override
            public Result<ChatResponse, LlmError> chat(ChatRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public <T> Result<T, LlmError> chatStructured(ChatRequest request, Class<T> targetType) {
                throw new UnsupportedOperationException();
            }
        };
        DefaultLlmClientRegistry registry = new DefaultLlmClientRegistry(
                Map.of("deepseek", STUB, "qwen", other), "qwen");

        assertThat(registry.primary()).isSameAs(other);
    }

    @Test
    void explicitPrimaryPointingToUnknownModelThrowsWithGuidance() {
        DefaultLlmClientRegistry registry = new DefaultLlmClientRegistry(Map.of("deepseek", STUB), "qwen");

        assertThatThrownBy(registry::primary)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("qwen")
                .hasMessageContaining("deepseek");
    }

    @Test
    void multipleModelsWithoutExplicitPrimaryThrows() {
        DefaultLlmClientRegistry registry = new DefaultLlmClientRegistry(
                Map.of("deepseek", STUB, "qwen", STUB), null);

        assertThatThrownBy(registry::primary)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolbox.llm.primary");
    }
}
