package cn.code91.toolbox.database.observability;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

import javax.sql.DataSource;
import java.lang.reflect.Constructor;

/**
 * <b>p6spy {@link DataSource} 透明包装 {@link BeanPostProcessor}</b>
 * <p>把容器中的任意 {@link DataSource} bean 包装为
 * {@code com.p6spy.engine.spy.P6DataSource}，从而让所有经该 DataSource 发出的 SQL
 * 被 p6spy 拦截打印（appender/format 等细节走 classpath 上的 {@code spy.properties}）。</p>
 *
 * <p><b>beacon ADR-0013 的性能教训</b>：{@code Class.forName("com.p6spy.engine.spy.P6DataSource")}
 * 与其构造器查找是相对昂贵的反射操作。若放在
 * {@link #postProcessAfterInitialization(Object, String)} 里，每个 bean 初始化后都会触发一次
 * ——即便本类只包装 {@link DataSource} 类型的 bean，反射查找本身仍在每次调用时重复执行。
 * 因此把 {@code Class.forName}/构造器解析移到<b>构造期</b>一次性完成并缓存为 {@code final} 字段，
 * 热路径（{@code postProcessAfterInitialization}）只做零反射查找的 {@code isInstance}/
 * {@code newInstance} 调用。</p>
 *
 * <p>本类由 {@code ToolboxDatabaseAutoConfiguration} 以 {@code static @Bean} 形式装配，
 * 仅在 {@code toolbox.database.p6spy.enabled=true} 且 {@code P6DataSource} 在 classpath 时生效
 * （默认关闭：p6spy 有运行时开销，属调试型能力）。</p>
 */
public final class P6spyDataSourceWrappingBeanPostProcessor implements BeanPostProcessor {

    private final Class<?> p6DataSourceClass;
    private final Constructor<?> p6DataSourceConstructor;

    /**
     * 构造期一次性解析并缓存 {@code P6DataSource} 类与其 {@code (DataSource)} 构造器。
     * 本类总是在 {@code @ConditionalOnClass(name = "com.p6spy.engine.spy.P6DataSource")}
     * 已确认该类在 classpath 之后才会被装配，{@link ReflectiveOperationException} 理论不可达，
     * 一旦发生说明装配条件与实际 classpath 状态不一致，属编程错误，故转译为
     * {@link IllegalStateException}（fail-fast，而非在热路径悄悄退化）。
     */
    public P6spyDataSourceWrappingBeanPostProcessor() {
        try {
            this.p6DataSourceClass = Class.forName("com.p6spy.engine.spy.P6DataSource");
            this.p6DataSourceConstructor = this.p6DataSourceClass.getConstructor(DataSource.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "P6DataSource 应已经由 @ConditionalOnClass 确认在 classpath 上，构造期解析失败属编程错误", e);
        }
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!(bean instanceof DataSource dataSource)) {
            return bean;
        }
        if (p6DataSourceClass.isInstance(dataSource)) {
            // 已经是 P6DataSource（或其子类）：不二次包装，避免嵌套代理
            return bean;
        }
        try {
            return p6DataSourceConstructor.newInstance(dataSource);
        } catch (ReflectiveOperationException e) {
            throw new P6spyWrapException("包装 DataSource 为 P6DataSource 失败: " + beanName, e);
        }
    }

    /**
     * {@link BeansException} 是 abstract class，BeanPostProcessor 的包装失败需要一个具体子类
     * 才能抛出——定义为私有内部类，仅本 BPP 内部使用。
     */
    private static final class P6spyWrapException extends BeansException {
        P6spyWrapException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }
}
