package cn.code91.toolbox.compare.spi;

/**
 * {@link CompareHandlerRegistry} 定制扩展点，装配期在内置与应用注册的 comparator/formatter
 * 收集完毕、{@code freeze()} 之前回调。
 */
public interface CompareRegistryCustomizer {

    void customize(CompareHandlerRegistry registry);
}
