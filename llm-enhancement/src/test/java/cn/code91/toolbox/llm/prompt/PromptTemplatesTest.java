package cn.code91.toolbox.llm.prompt;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.llm.core.LlmError;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PromptTemplates} 单测（brief 交付物）：正常替换、缺模板、缺变量语义。
 * 无 Spring 容器场景下直接使用默认 {@link ClasspathPromptTemplateLoader}。
 */
class PromptTemplatesTest {

    @Test
    void rendersPlaceholdersFromClasspathTemplate() {
        Result<String, LlmError> result = PromptTemplates.render(
                "greeting", Map.of("name", "小明", "day", "周一"));

        assertThat(result.isOk()).isTrue();
        assertThat(result.get()).contains("你好，小明！今天是周一。");
    }

    @Test
    void missingVariableLeavesPlaceholderAsIs() {
        Result<String, LlmError> result = PromptTemplates.render(
                "greeting", Map.of("name", "小明", "day", "周一"));

        assertThat(result.get()).contains("缺失变量占位符：${missing}");
    }

    @Test
    void explicitNullValueRendersAsEmptyString() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("name", "小明");
        vars.put("day", null);
        Result<String, LlmError> result = PromptTemplates.render("greeting", vars);

        assertThat(result.get()).contains("你好，小明！今天是。");
    }

    @Test
    void nullVarsMapTreatedAsEmpty() {
        Result<String, LlmError> result = PromptTemplates.render("greeting", null);

        assertThat(result.isOk()).isTrue();
        assertThat(result.get()).contains("${name}", "${day}");
    }

    @Test
    void missingTemplateReturnsProviderErrorWithTemplateNotFoundCode() {
        Result<String, LlmError> result = PromptTemplates.render("does-not-exist", Map.of());

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(LlmError.ProviderError.class);
        LlmError.ProviderError err = (LlmError.ProviderError) result.getErr();
        assertThat(err.providerCode()).isEqualTo("template-not-found");
        assertThat(err.message()).contains("does-not-exist");
    }
}
