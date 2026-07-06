package cn.code91.toolbox.compare.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注字段的展示标签。
 *
 * <p>解析顺序（两级回退）：{@code messageKey} 经 facility {@code LocaleUtil} 解析成功则采用；
 * 解析不到（含未提供 {@code messageKey}）回退 {@link #value()}；{@link #value()} 为空回退字段名。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CompareLabel {

    /**
     * 展示标签的兜底文案；为空时回退字段名。
     */
    String value() default "";

    /**
     * i18n 消息键；优先于 {@link #value()} 解析。
     */
    String messageKey() default "";
}
