package cn.code91.toolbox.llm.openai;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OpenAiModelConfig} 紧凑构造器校验（裁定 D 启动期 fail-fast）：缺 base-url/api-key/model
 * 均报 {@link IllegalStateException} 并指明配置路径；默认值回退。
 */
class OpenAiModelConfigTest {

    @Test
    void missingBaseUrlFailsFastWithConfigPathGuidance() {
        assertThatThrownBy(() -> new OpenAiModelConfig("deepseek", null, "sk-x", "deepseek-chat",
                null, null, null, 2, null, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deepseek")
                .hasMessageContaining("toolbox.llm.models.deepseek.base-url");
    }

    @Test
    void blankBaseUrlFailsFast() {
        assertThatThrownBy(() -> new OpenAiModelConfig("deepseek", "  ", "sk-x", "deepseek-chat",
                null, null, null, 2, null, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("base-url");
    }

    @Test
    void missingApiKeyFailsFastWithConfigPathGuidance() {
        assertThatThrownBy(() -> new OpenAiModelConfig("deepseek", "https://api.deepseek.com/v1", null,
                "deepseek-chat", null, null, null, 2, null, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toolbox.llm.models.deepseek.api-key");
    }

    @Test
    void missingModelFailsFastWithConfigPathGuidance() {
        assertThatThrownBy(() -> new OpenAiModelConfig("deepseek", "https://api.deepseek.com/v1",
                "sk-x", null, null, null, null, 2, null, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toolbox.llm.models.deepseek.model");
    }

    @Test
    void blankModelNameRejected() {
        assertThatThrownBy(() -> new OpenAiModelConfig("  ", "https://x/v1", "sk-x", "m",
                null, null, null, 2, null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void defaultsAppliedWhenTimeoutBackoffAndRetriesUnset() {
        OpenAiModelConfig config = new OpenAiModelConfig("deepseek", "https://api.deepseek.com/v1",
                "sk-x", "deepseek-chat", null, null, null, -1, null, 0);

        assertThat(config.timeout()).isEqualTo(Duration.ofSeconds(60));
        assertThat(config.retryBackoff()).isEqualTo(Duration.ofMillis(500));
        assertThat(config.maxRetries()).isEqualTo(2);
    }

    @Test
    void toStringNeverExposesApiKey() {
        OpenAiModelConfig config = new OpenAiModelConfig("deepseek", "https://api.deepseek.com/v1",
                "sk-super-secret-value", "deepseek-chat", 0.2, 512, Duration.ofSeconds(30), 2,
                Duration.ofMillis(500), 5);

        String rendered = config.toString();

        assertThat(rendered).doesNotContain("sk-super-secret-value");
        assertThat(rendered).contains("******");
        assertThat(rendered).as("其余字段仍应正常输出，只有 apiKey 被遮蔽").contains("deepseek-chat", "deepseek");
    }
}
