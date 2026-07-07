package cn.code91.toolbox.compare.engine;

import cn.code91.toolbox.compare.annotation.CompareWith;
import cn.code91.toolbox.compare.spi.ValueComparator;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;

/**
 * 单个参与对比字段的缓存元数据：取值句柄 + 注解元数据（字段名、{@code @CompareLabel} 所在
 * {@link Field} 引用）+ 字段级比较器实例。
 *
 * <p><b>标签不在此缓存</b>（I1 修复）：{@link #resolveLabel()} 每次调用都现查现解析，
 * 而非在类维度缓存解析结果字符串——{@code @CompareLabel(messageKey=...)} 的解析依赖调用时的
 * {@code LocaleContextHolder} locale，若把解析结果缓存在按 {@code Class} 键控的
 * {@link ClassMetaCache} 里，首个触发线程的 locale 会被后续所有请求永久继承。
 *
 * @param name          字段名（也是路径片段与标签兜底值）
 * @param field         字段反射引用，供 {@link #resolveLabel()} 现查 {@code @CompareLabel} 注解
 * @param getter        取值句柄（JavaBean 为 getter 方法句柄，record 为 accessor 方法句柄）
 * @param compareWith   字段级 {@code @CompareWith} 指定的比较器实例；未标注时为 null
 */
record FieldMeta(String name, Field field, MethodHandle getter, ValueComparator<Object> compareWith) {

    /**
     * 现查现解析展示标签（messageKey → value → 字段名两级回退，见 {@link LabelResolver}），
     * 每次调用都重新解析，不缓存结果——解析结果依赖调用时的 locale。
     */
    String resolveLabel() {
        return LabelResolver.resolve(field);
    }

    Object getValue(Object target) {
        try {
            return getter.invoke(target);
        } catch (Throwable e) {
            throw new FieldReadException(e);
        }
    }

    /**
     * 字段读取失败时抛出的内部短路信号，由调用方（{@link cn.code91.toolbox.compare.engine.ReflectionDiffEngine}）
     * 捕获后结合当前遍历路径转换为 {@link cn.code91.toolbox.compare.core.FieldAccessError}。
     */
    static final class FieldReadException extends RuntimeException {
        FieldReadException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    @SuppressWarnings("unchecked")
    static ValueComparator<Object> resolveCompareWith(java.lang.reflect.Field field) {
        CompareWith annotation = field.getAnnotation(CompareWith.class);
        if (annotation == null) {
            return null;
        }
        try {
            Class<? extends ValueComparator<?>> comparatorClass = annotation.value();
            return (ValueComparator<Object>) comparatorClass.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "无法实例化 @CompareWith 指定的比较器：" + annotation.value().getName(), e);
        }
    }
}
