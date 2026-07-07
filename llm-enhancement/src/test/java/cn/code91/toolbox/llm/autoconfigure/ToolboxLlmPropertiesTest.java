package cn.code91.toolbox.llm.autoconfigure;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ToolboxLlmProperties} 紧凑构造器与嵌套 record 单测：null 集合/嵌套对象回退默认值、
 * {@code Model.toString()} 不泄露 api-key（全局约束 12）。
 */
class ToolboxLlmPropertiesTest {

    @Test
    void nullModelsMapDefaultsToEmptyMap() {
        ToolboxLlmProperties properties = new ToolboxLlmProperties(true, null, null, null);

        assertThat(properties.models()).isEmpty();
    }

    @Test
    void nullLogDefaultsToEnabledAndMaskContentTrue() {
        ToolboxLlmProperties properties = new ToolboxLlmProperties(true, null, null, Map.of());

        assertThat(properties.log().enabled()).isTrue();
        assertThat(properties.log().maskContent()).isTrue();
    }

    @Test
    void modelTypeDefaultsToOpenAiCompatibleWhenBlank() {
        ToolboxLlmProperties.Model model = new ToolboxLlmProperties.Model(
                "  ", "https://x/v1", "sk-x", "m", null, null, null, null, null);

        assertThat(model.type()).isEqualTo("openai-compatible");
    }

    @Test
    void modelTypeDefaultsToOpenAiCompatibleWhenNull() {
        ToolboxLlmProperties.Model model = new ToolboxLlmProperties.Model(
                null, "https://x/v1", "sk-x", "m", null, null, null, null, null);

        assertThat(model.type()).isEqualTo("openai-compatible");
    }

    @Test
    void modelToStringNeverExposesApiKey() {
        ToolboxLlmProperties.Model model = new ToolboxLlmProperties.Model(
                "openai-compatible", "https://api.deepseek.com/v1", "sk-super-secret-value",
                "deepseek-chat", 0.2, 512, Duration.ofSeconds(30), 2, 5.0);

        String rendered = model.toString();

        assertThat(rendered).doesNotContain("sk-super-secret-value");
        assertThat(rendered).contains("******");
        assertThat(rendered).contains("deepseek-chat");
    }
}
