package cn.code91.toolbox.database.params;

import java.lang.reflect.Field;

/**
 * <b>单字段映射记录</b>
 *
 * @param javaFieldName Java 字段名（如 {@code userName}）；用作 {@link AnnotatedParameterSource}
 *                       双 key 中的第一 key（与 Spring JDBC {@code BeanPropertySqlParameterSource}
 *                       惯例一致），也是 SqlBuilder 反射取值时的字段定位依据
 * @param sqlColumnName  SQL 列名（如 {@code user_name}）；来自 {@code @Column.value} 显式指定，
 *                       或回退 {@link cn.code91.toolbox.database.naming.ColumnNamingStrategy} 推算；
 *                       用作双 key 中的第二 key，以及 SqlBuilder 生成 SQL 的列名/占位符
 * @param field          反射 {@link Field} 句柄（已 {@code setAccessible(true)}）
 * @param isId           {@code @Id} 注解标记；SqlBuilder 据此定位 WHERE/SET 子句排除的列
 */
public record FieldMapping(
        String javaFieldName,
        String sqlColumnName,
        Field field,
        boolean isId) {
}
