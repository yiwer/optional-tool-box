package cn.code91.toolbox.llm.prompt;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ClasspathPromptTemplateLoaderTest {

    private final ClasspathPromptTemplateLoader loader = new ClasspathPromptTemplateLoader();

    @Test
    void loadsExistingTemplateContent() {
        Optional<String> content = loader.load("greeting");

        assertThat(content).isPresent();
        assertThat(content.get()).contains("${name}");
    }

    @Test
    void returnsEmptyForMissingTemplate() {
        assertThat(loader.load("does-not-exist")).isEmpty();
    }

    @Test
    void returnsEmptyForBlankOrNullName() {
        assertThat(loader.load(null)).isEmpty();
        assertThat(loader.load("")).isEmpty();
        assertThat(loader.load("   ")).isEmpty();
    }
}
