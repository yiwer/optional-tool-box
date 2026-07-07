package cn.code91.toolbox.database.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <b>标记 Java 字段为主键</b>
 * <p>P1 仅是标记：主键值由调用方外部赋值，本模块不提供生成策略（雪花/自增等策略属 P3，
 * 本任务明确排除）。Task 8 的 SqlBuilder/JdbcRepository 据此定位 WHERE/SET 子句排除的列。</p>
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Id {
}
