package cn.code91.toolbox.database.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <b>排除 Java 字段，不参与 SQL</b>
 * <p>用于计算字段、辅助字段等。Task 8 的 {@code AnnotatedParameterSource} 构造 ParameterSource 时
 * 跳过标注此注解的字段。</p>
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Transient {
}
