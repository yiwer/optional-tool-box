package cn.code91.toolbox.compare.engine;

import cn.code91.toolbox.compare.annotation.CompareWith;
import cn.code91.toolbox.compare.spi.ValueComparator;

import java.lang.invoke.MethodHandle;

/**
 * 单个参与对比字段的缓存元数据：取值句柄 + 标签 + 字段级比较器实例。
 *
 * @param name          字段名（也是路径片段与标签兜底值）
 * @param label         展示标签解析结果（已应用 messageKey → value → 字段名两级回退，见 {@link LabelResolver}）
 * @param getter        取值句柄（JavaBean 为 getter 方法句柄，record 为 accessor 方法句柄）
 * @param compareWith   字段级 {@code @CompareWith} 指定的比较器实例；未标注时为 null
 */
record FieldMeta(String name, String label, MethodHandle getter, ValueComparator<Object> compareWith) {

    Object getValue(Object target) {
        try {
            return getter.invoke(target);
        } catch (Throwable e) {
            throw new FieldReadException(name, e);
        }
    }

    /**
     * 字段读取失败时抛出，携带字段名供上层转换为 {@link cn.code91.toolbox.compare.core.FieldAccessError}。
     */
    static final class FieldReadException extends RuntimeException {
        private final String fieldName;

        FieldReadException(String fieldName, Throwable cause) {
            super(cause.getMessage(), cause);
            this.fieldName = fieldName;
        }

        String fieldName() {
            return fieldName;
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
