package cn.code91.toolbox.llm.autoconfigure;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.llm.core.ChatRequest;
import cn.code91.toolbox.llm.core.ChatResponse;
import cn.code91.toolbox.llm.core.LlmClient;
import cn.code91.toolbox.llm.core.LlmClientRegistry;
import cn.code91.toolbox.llm.core.LlmError;
import cn.code91.toolbox.llm.core.Usage;
import cn.code91.toolbox.llm.spi.PromptTemplateLoader;
import cn.code91.toolbox.llm.spi.UsageListener;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 装配矩阵（brief）：无 models 配置 → 空 registry 可启动且 get()/primary() 消息含配置引导；
 * 缺 base-url/api-key/model → 启动失败且报错含配置路径（裁定 D fail-fast）；primary 三级解析
 * 各态；UsageListener 多实例全回调且回调抛异常不影响 chat 结果；用户覆盖 registry/loader 让位；
 * enabled=false 全不装配；属性默认值绑定。
 */
class ToolboxLlmAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolboxLlmAutoConfiguration.class));

    private WireMockServer wireMock;

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(0);
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
    }

    @AfterEach
    void stopWireMock() {
        wireMock.stop();
    }

    @Test
    void defaultAssemblyRegistersPromptTemplateLoaderAndEmptyRegistry() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PromptTemplateLoader.class);
            assertThat(context).hasSingleBean(LlmClientRegistry.class);
        });
    }

    @Test
    void enabledFalseAssemblesNothing() {
        contextRunner
                .withPropertyValues("toolbox.llm.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(LlmClientRegistry.class);
                    assertThat(context).doesNotHaveBean(PromptTemplateLoader.class);
                });
    }

    @Test
    void zeroModelsAssembleEmptyRegistryWithBootstrapGuidanceOnGetAndPrimary() {
        contextRunner.run(context -> {
            LlmClientRegistry registry = context.getBean(LlmClientRegistry.class);

            assertThatThrownBy(() -> registry.get("deepseek"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("deepseek")
                    .hasMessageContaining("toolbox.llm.models");
            assertThatThrownBy(registry::primary)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("toolbox.llm.models");
        });
    }

    @Test
    void modelMissingBaseUrlFailsContextStartup() {
        contextRunner
                .withPropertyValues(
                        "toolbox.llm.models.deepseek.api-key=sk-x",
                        "toolbox.llm.models.deepseek.model=deepseek-chat")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("toolbox.llm.models.deepseek.base-url");
                });
    }

    @Test
    void modelMissingApiKeyFailsContextStartup() {
        contextRunner
                .withPropertyValues(
                        "toolbox.llm.models.deepseek.base-url=https://api.deepseek.com/v1",
                        "toolbox.llm.models.deepseek.model=deepseek-chat")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("toolbox.llm.models.deepseek.api-key");
                });
    }

    @Test
    void modelMissingModelFieldFailsContextStartup() {
        contextRunner
                .withPropertyValues(
                        "toolbox.llm.models.deepseek.base-url=https://api.deepseek.com/v1",
                        "toolbox.llm.models.deepseek.api-key=sk-x")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("toolbox.llm.models.deepseek.model");
                });
    }

    @Test
    void unrecognizedModelTypeFailsContextStartup() {
        contextRunner
                .withPropertyValues(
                        "toolbox.llm.models.deepseek.type=anthropic-native",
                        "toolbox.llm.models.deepseek.base-url=https://api.deepseek.com/v1",
                        "toolbox.llm.models.deepseek.api-key=sk-x",
                        "toolbox.llm.models.deepseek.model=deepseek-chat")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("anthropic-native");
                });
    }

    @Test
    void singleConfiguredModelIsPrimaryWithoutExplicitConfig() {
        contextRunner
                .withPropertyValues(validModelProperties("deepseek"))
                .run(context -> {
                    LlmClientRegistry registry = context.getBean(LlmClientRegistry.class);
                    assertThat(registry.primary()).isSameAs(registry.get("deepseek"));
                });
    }

    @Test
    void explicitPrimaryWinsOverMultipleModels() {
        contextRunner
                .withPropertyValues(mergeProperties(
                        validModelProperties("deepseek"), validModelProperties("qwen"),
                        new String[]{"toolbox.llm.primary=qwen"}))
                .run(context -> {
                    LlmClientRegistry registry = context.getBean(LlmClientRegistry.class);
                    assertThat(registry.primary()).isSameAs(registry.get("qwen"));
                });
    }

    @Test
    void multipleModelsWithoutExplicitPrimaryFailsOnPrimaryCall() {
        contextRunner
                .withPropertyValues(mergeProperties(
                        validModelProperties("deepseek"), validModelProperties("qwen"), new String[0]))
                .run(context -> {
                    LlmClientRegistry registry = context.getBean(LlmClientRegistry.class);
                    assertThatThrownBy(registry::primary)
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageContaining("toolbox.llm.primary");
                });
    }

    @Test
    void allUsageListenersNotifiedAndThrowingListenerDoesNotAffectChatResult() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody("""
                        {"model":"deepseek-chat","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}
                        """)));
        List<Usage> secondCaptured = new ArrayList<>();
        contextRunner
                .withUserConfiguration(ListenersConfig.class)
                .withBean("secondCaptureListener", UsageListener.class, () -> (modelName, usage, latency, traceId) -> secondCaptured.add(usage))
                .withPropertyValues(wireMockModelProperties("deepseek"))
                .run(context -> {
                    LlmClientRegistry registry = context.getBean(LlmClientRegistry.class);

                    Result<ChatResponse, LlmError> result = registry.primary().chat(ChatRequest.user("hi"));

                    assertThat(result.isOk()).as("回调抛异常不得影响 chat 结果").isTrue();
                    assertThat(secondCaptured).as("throwing listener 之后的 listener 仍须被回调").hasSize(1);
                });
    }

    /**
     * 指向本测试类起的 WireMock 实例的模型配置（端到端验证装配层确实把 listener 收集并
     * 传给真正会发出 HTTP 请求的 adapter，而非仅验证 bean 图存在）。
     */
    private String[] wireMockModelProperties(String name) {
        return new String[]{
                "toolbox.llm.models." + name + ".base-url=http://localhost:" + wireMock.port() + "/v1",
                "toolbox.llm.models." + name + ".api-key=sk-" + name,
                "toolbox.llm.models." + name + ".model=" + name + "-chat"
        };
    }

    @Test
    void userDefinedPromptTemplateLoaderAndRegistryTakePrecedence() {
        contextRunner
                .withUserConfiguration(UserSeamOverridesConfig.class)
                .run(context -> {
                    assertThat(context.getBean(PromptTemplateLoader.class))
                            .isInstanceOf(UserSeamOverridesConfig.StubLoader.class);
                    assertThat(context.getBean(LlmClientRegistry.class))
                            .isInstanceOf(UserSeamOverridesConfig.StubRegistry.class);
                });
    }

    @Test
    void unconfiguredPropertiesBindToDocumentedDefaults() {
        contextRunner
                .withPropertyValues(validModelProperties("deepseek"))
                .run(context -> {
                    ToolboxLlmProperties properties = context.getBean(ToolboxLlmProperties.class);

                    assertThat(properties.log().enabled()).as("log.enabled 默认 true（全局约束 12）").isTrue();
                    assertThat(properties.log().maskContent()).as("log.mask-content 默认 true（全局约束 12）").isTrue();
                    ToolboxLlmProperties.Model model = properties.models().get("deepseek");
                    assertThat(model.type()).isEqualTo("openai-compatible");
                });
    }

    private static String[] validModelProperties(String name) {
        return new String[]{
                "toolbox.llm.models." + name + ".base-url=https://api.example.com/v1",
                "toolbox.llm.models." + name + ".api-key=sk-" + name,
                "toolbox.llm.models." + name + ".model=" + name + "-chat"
        };
    }

    private static String[] mergeProperties(String[] a, String[] b, String[] c) {
        String[] merged = new String[a.length + b.length + c.length];
        System.arraycopy(a, 0, merged, 0, a.length);
        System.arraycopy(b, 0, merged, a.length, b.length);
        System.arraycopy(c, 0, merged, a.length + b.length, c.length);
        return merged;
    }

    @Configuration(proxyBeanMethods = false)
    static class ListenersConfig {

        @Bean
        UsageListener firstListener() {
            return (modelName, usage, latency, traceId) -> { };
        }

        @Bean
        UsageListener secondListener() {
            return (modelName, usage, latency, traceId) -> {
                throw new IllegalStateException("listener boom");
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserSeamOverridesConfig {

        @Bean
        PromptTemplateLoader promptTemplateLoader() {
            return new StubLoader();
        }

        @Bean
        LlmClientRegistry llmClientRegistry() {
            return new StubRegistry();
        }

        static final class StubLoader implements PromptTemplateLoader {
            @Override
            public Optional<String> load(String name) {
                return Optional.empty();
            }
        }

        static final class StubRegistry implements LlmClientRegistry {
            @Override
            public LlmClient get(String modelName) {
                throw new UnsupportedOperationException();
            }

            @Override
            public LlmClient primary() {
                throw new UnsupportedOperationException();
            }
        }
    }
}
