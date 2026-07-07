package cn.code91.toolbox.compare.autoconfigure;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.compare.core.CompareError;
import cn.code91.toolbox.compare.core.DiffEngine;
import cn.code91.toolbox.compare.core.DiffResult;
import cn.code91.toolbox.compare.render.JsonRenderer;
import cn.code91.toolbox.compare.render.PlainTextRenderer;
import cn.code91.toolbox.compare.spi.CompareHandlerRegistry;
import cn.code91.toolbox.compare.spi.CompareRegistryCustomizer;
import cn.code91.toolbox.compare.spi.ValueComparator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 装配测试：enabled 开关、Seam 覆盖（@ConditionalOnMissingBean）、SPI 收集顺序、
 * freeze 后 register 抛异常、messageKey 经真实 MessageSource 成功解析。
 */
class ToolboxCompareAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolboxCompareAutoConfiguration.class));

    @Test
    void defaultAssemblyRegistersAllBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CompareHandlerRegistry.class);
            assertThat(context).hasSingleBean(DiffEngine.class);
            assertThat(context).hasSingleBean(PlainTextRenderer.class);
            assertThat(context).hasSingleBean(JsonRenderer.class);
            assertThat(context).hasBean("toolboxCompareMessageSource");
        });
    }

    @Test
    void unconfiguredPropertiesBindToDocumentedDefaults() {
        contextRunner.run(context -> {
            ToolboxCompareProperties properties = context.getBean(ToolboxCompareProperties.class);

            assertThat(properties.maxDepth()).as("未配置 max-depth 时应回退 8，而非 int 原生默认值 0").isEqualTo(8);
            assertThat(properties.nullAsEmpty()).isFalse();
            assertThat(properties.datePattern()).isEqualTo("yyyy-MM-dd HH:mm:ss");
        });
    }

    @Test
    void enabledFalseAssemblesNothing() {
        contextRunner.withPropertyValues("toolbox.compare.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(DiffEngine.class);
            assertThat(context).doesNotHaveBean(CompareHandlerRegistry.class);
            assertThat(context).doesNotHaveBean(PlainTextRenderer.class);
            assertThat(context).doesNotHaveBean(JsonRenderer.class);
        });
    }

    @Test
    void userDefinedDiffEngineTakesPrecedence() {
        contextRunner.withUserConfiguration(CustomDiffEngineConfig.class).run(context -> {
            assertThat(context).hasSingleBean(DiffEngine.class);
            assertThat(context.getBean(DiffEngine.class)).isInstanceOf(CustomDiffEngineConfig.StubDiffEngine.class);
        });
    }

    @Test
    void applicationRegisteredComparatorIsCollectedIntoRegistry() {
        contextRunner.withUserConfiguration(CustomComparatorConfig.class).run(context -> {
            CompareHandlerRegistry registry = context.getBean(CompareHandlerRegistry.class);
            ValueComparator<String> found = registry.findComparator(String.class);
            assertThat(found).isNotNull();
            assertThat(found.isEqual("x", "y")).as("自定义 comparator 应生效（始终相等）").isTrue();
        });
    }

    @Test
    void registryCustomizerIsAppliedBeforeFreeze() {
        contextRunner.withUserConfiguration(CustomizerConfig.class).run(context -> {
            CompareHandlerRegistry registry = context.getBean(CompareHandlerRegistry.class);
            assertThat(registry.isFrozen()).isTrue();
            assertThat(CustomizerConfig.CUSTOMIZE_CALLED).isTrue();
        });
    }

    @Test
    void registryIsFrozenAndRejectsFurtherRegistration() {
        contextRunner.run((AssertableApplicationContext context) -> {
            CompareHandlerRegistry registry = context.getBean(CompareHandlerRegistry.class);
            assertThat(registry.isFrozen()).isTrue();
            assertThatRegisterAfterFreezeThrows(registry);
        });
    }

    @Test
    void configuredMaxDepthIsHonoredByAssembledDiffEngineDefaultOptions() {
        // ChainChild -> ChainChild -> ... 深度 3 层，配置 max-depth=1 时应触发 DepthExceeded。
        contextRunner.withPropertyValues("toolbox.compare.max-depth=1").run(context -> {
            DiffEngine engine = context.getBean(DiffEngine.class);
            ChainNode before = new ChainNode("a", new ChainNode("b", new ChainNode("c", null)));
            ChainNode after = new ChainNode("a", new ChainNode("b", new ChainNode("d", null)));

            Result<DiffResult, CompareError> result = engine.diff(before, after);

            assertThat(result.isErr())
                    .as("toolbox.compare.max-depth=1 应让无显式 DiffOptions 的 diff() 也在深度 1 处截断")
                    .isTrue();
        });
    }

    /**
     * I4 修复：{@code order.amount} 曾是误留在生产 bundle
     * （{@code src/main/resources/i18n/toolbox-compare-messages*.properties}）的示例/测试键——
     * facility {@code AggregatedMessageSource}（{@code @Primary}）会把它聚合暴露给任何引入
     * compare 的应用，高碰撞命名可能遮蔽消费方同名键。本测试用
     * {@link TestOnlyMessageSourceConfig} 覆盖 {@code toolboxCompareMessageSource} bean，
     * 指向仅存在于 {@code src/test/resources} 的测试专用 bundle，既验证 messageKey 确实
     * "经真实 MessageSource 解析"（而非绕过 Spring 直接读 properties），又不再依赖生产 bundle
     * 携带示例键。
     */
    @Test
    void messageKeyResolvesThroughWiredMessageSourceInBothLocales() {
        contextRunner.withUserConfiguration(TestOnlyMessageSourceConfig.class).run(context -> {
            MessageSource messageSource = context.getBean("toolboxCompareMessageSource", MessageSource.class);

            String zh = messageSource.getMessage("order.amount", null, Locale.SIMPLIFIED_CHINESE);
            String en = messageSource.getMessage("order.amount", null, Locale.ENGLISH);

            assertThat(zh).isEqualTo("订单金额");
            assertThat(en).isEqualTo("Order Amount");
        });
    }

    /**
     * I4 回归护栏：默认装配（未被测试配置覆盖）下的生产 {@code toolboxCompareMessageSource}
     * bean 不应再解析出 {@code order.amount}——证明该示例键已从生产 bundle 移除，不会通过
     * facility {@code AggregatedMessageSource} 泄漏给消费方。
     */
    @Test
    void productionMessageSourceNoLongerLeaksExampleKey() {
        contextRunner.run(context -> {
            MessageSource messageSource = context.getBean("toolboxCompareMessageSource", MessageSource.class);

            assertThatThrownBy(() -> messageSource.getMessage("order.amount", null, Locale.ENGLISH))
                    .as("生产 bundle 不应再携带 order.amount 示例键")
                    .isInstanceOf(NoSuchMessageException.class);
        });
    }

    private static void assertThatRegisterAfterFreezeThrows(CompareHandlerRegistry registry) {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> registry.registerComparator(new ValueComparator<Integer>() {
                    @Override
                    public Class<Integer> type() {
                        return Integer.class;
                    }

                    @Override
                    public boolean isEqual(Integer a, Integer b) {
                        return true;
                    }
                }))
                .isInstanceOf(IllegalStateException.class);
    }

    @Configuration
    static class CustomDiffEngineConfig {
        @Bean
        DiffEngine diffEngine() {
            return new StubDiffEngine();
        }

        static final class StubDiffEngine implements DiffEngine {
            @Override
            public <T> Result<DiffResult, CompareError> diff(T oldValue, T newValue) {
                return Result.ok(new DiffResult(java.util.List.of()));
            }

            @Override
            public <T> Result<DiffResult, CompareError> diff(T oldValue, T newValue, cn.code91.toolbox.compare.core.DiffOptions options) {
                return diff(oldValue, newValue);
            }
        }
    }

    @Configuration
    static class CustomComparatorConfig {
        @Bean
        ValueComparator<String> alwaysEqualStringComparator() {
            return new ValueComparator<>() {
                @Override
                public Class<String> type() {
                    return String.class;
                }

                @Override
                public boolean isEqual(String a, String b) {
                    return true;
                }
            };
        }
    }

    /**
     * I4 修复：测试专用 {@code toolboxCompareMessageSource} bean，指向仅存在于
     * {@code src/test/resources/i18n/test-compare-messages*.properties} 的测试 bundle
     * （bean 名与生产 bean 相同，经 {@code @ConditionalOnMissingBean(name=...)} 让位后由本配置接管）。
     */
    @Configuration
    static class TestOnlyMessageSourceConfig {
        @Bean("toolboxCompareMessageSource")
        ResourceBundleMessageSource toolboxCompareMessageSource() {
            ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
            messageSource.setBasename("i18n/test-compare-messages");
            messageSource.setDefaultEncoding("UTF-8");
            messageSource.setUseCodeAsDefaultMessage(false);
            messageSource.setFallbackToSystemLocale(false);
            return messageSource;
        }
    }

    @Configuration
    static class CustomizerConfig {
        static volatile boolean CUSTOMIZE_CALLED = false;

        @Bean
        CompareRegistryCustomizer compareRegistryCustomizer() {
            return registry -> CUSTOMIZE_CALLED = true;
        }
    }

    /**
     * 链式嵌套测试夹具，供 {@link #configuredMaxDepthIsHonoredByAssembledDiffEngineDefaultOptions} 使用。
     */
    public static final class ChainNode {
        private String value;
        private ChainNode next;

        public ChainNode() {
        }

        public ChainNode(String value, ChainNode next) {
            this.value = value;
            this.next = next;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }

        public ChainNode getNext() {
            return next;
        }

        public void setNext(ChainNode next) {
            this.next = next;
        }
    }
}
