package cn.code91.toolbox.llm.prompt;

import cn.code91.toolbox.llm.spi.PromptTemplateLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * {@link PromptTemplateLoader} 默认实现（P1 默认，03 §4.3）：只查
 * {@code classpath:prompts/{name}.md}。模板平台化（热更新/灰度）不做，仅"模板随版本进代码库"这一层。
 */
public final class ClasspathPromptTemplateLoader implements PromptTemplateLoader {

    private static final String DIRECTORY = "prompts/";
    private static final String EXTENSION = ".md";

    @Override
    public Optional<String> load(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String location = DIRECTORY + name + EXTENSION;
        try (InputStream stream = ClasspathPromptTemplateLoader.class.getClassLoader().getResourceAsStream(location)) {
            if (stream == null) {
                return Optional.empty();
            }
            return Optional.of(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 读取期 IO 异常（极罕见：resource 存在但读取中失败）等同"未找到"处理，
            // 上层 PromptTemplates 统一以缺模板语义报错，不在此处新增异常通路。
            return Optional.empty();
        }
    }
}
