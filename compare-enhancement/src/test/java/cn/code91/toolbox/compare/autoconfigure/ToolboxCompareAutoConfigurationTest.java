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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    void messageKeyResolvesThroughWiredMessageSourceInBothLocales() {
        contextRunner.run(context -> {
            MessageSource messageSource = context.getBean("toolboxCompareMessageSource", MessageSource.class);

            String zh = messageSource.getMessage("order.amount", null, Locale.SIMPLIFIED_CHINESE);
            String en = messageSource.getMessage("order.amount", null, Locale.ENGLISH);

            assertThat(zh).isEqualTo("订单金额");
            assertThat(en).isEqualTo("Order Amount");
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
