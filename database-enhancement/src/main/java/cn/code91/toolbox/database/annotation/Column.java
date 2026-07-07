package cn.code91.toolbox.database.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <b>显式声明 Java 字段对应的 SQL 列名与方言类型别名</b>
 * <p>{@code value} 缺省（空串）时由
 * {@link cn.code91.toolbox.database.naming.ColumnNamingStrategy} 推算列名。
 * {@code sqlType} 缺省（空串）时不做别名路由，handler 解析完全按 Java 类型
 * （{@link cn.code91.toolbox.database.registry.FieldHandlerRegistry#findHandler}）；
 * 显式给出时（如 {@code "jsonb"}/{@code "uuid"}/{@code "inet"}）精确路由到方言特化 handler
 * （{@code bySqlTypeAlias} 索引），用于同一 Java 类型有多种 SQL 表示的场景。</p>
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Column {

    /** SQL 列名；缺省走命名策略。 */
    String value() default "";

    /** 方言类型别名（如 "jsonb"/"uuid"/"inet"）；缺省不路由特化 handler，仅按 Java 类型匹配。 */
    String sqlType() default "";
}
