package cn.code91.toolbox.compare.autoconfigure;

import cn.code91.toolbox.compare.core.DiffEngine;
import cn.code91.toolbox.compare.core.DiffOptions;
import cn.code91.toolbox.compare.engine.ReflectionDiffEngine;
import cn.code91.toolbox.compare.render.JsonRenderer;
import cn.code91.toolbox.compare.render.PlainTextRenderer;
import cn.code91.toolbox.compare.spi.CompareHandlerRegistry;
import cn.code91.toolbox.compare.spi.CompareRegistryCustomizer;
import cn.code91.toolbox.compare.spi.ValueComparator;
import cn.code91.toolbox.compare.spi.ValueFormatter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.util.Set;

/**
 * compare-enhancement 自动装配入口。无 L1 条件（零第三方运行时依赖）、无 L3 选型；
 * L2 总开关默认 true，L4 每个核心 bean 均可被应用覆盖。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "toolbox.compare", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ToolboxCompareProperties.class)
public class ToolboxCompareAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CompareHandlerRegistry compareHandlerRegistry(
            ObjectProvider<ValueComparator<?>> comparators,
            ObjectProvider<ValueFormatter<?>> formatters,
            ObjectProvider<CompareRegistryCustomizer> customizers) {
        CompareHandlerRegistry registry = CompareHandlerRegistry.withBuiltins();
        comparators.orderedStream().forEach(registry::registerComparator);
        formatters.orderedStream().forEach(registry::registerFormatter);
        customizers.orderedStream().forEach(customizer -> customizer.customize(registry));
        registry.freeze();
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public DiffEngine diffEngine(CompareHandlerRegistry registry, ToolboxCompareProperties properties) {
        DiffOptions defaultOptions = new DiffOptions(properties.maxDepth(), properties.nullAsEmpty(), Set.of(), Set.of());
        return new ReflectionDiffEngine(registry, properties.datePattern(), defaultOptions);
    }

    @Bean
    @ConditionalOnMissingBean
    public PlainTextRenderer plainTextRenderer(ToolboxCompareProperties properties) {
        ToolboxCompareProperties.Render render = properties.render();
        return new PlainTextRenderer(render.modifiedTemplate(), render.addedTemplate(), render.removedTemplate());
    }

    @Bean
    @ConditionalOnMissingBean
    public JsonRenderer jsonRenderer() {
        return new JsonRenderer();
    }

    @Bean("toolboxCompareMessageSource")
    @ConditionalOnMissingBean(name = "toolboxCompareMessageSource")
    public ResourceBundleMessageSource toolboxCompareMessageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("i18n/toolbox-compare-messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(false);
        // 关闭系统 locale 回退：未匹配到具体 locale bundle（如 en）时应落回基础 bundle，
        // 而非运行 JVM 的系统默认 locale（否则测试/生产环境的系统 locale 会左右解析结果）。
        messageSource.setFallbackToSystemLocale(false);
        return messageSource;
    }
}
