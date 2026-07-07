package cn.code91.toolbox.database.naming;

/**
 * <b>列命名策略 Seam</b>
 * <p>Java 字段名 → SQL 列名的转换点。{@code @Column} 未显式指定列名时，装配/映射代码回退到此策略。
 * 装配层默认注册 {@link #CAMEL_TO_UNDERSCORE}，应用可通过 {@code @Bean} 覆盖（L4 Seam）。</p>
 */
@FunctionalInterface
public interface ColumnNamingStrategy {

    /**
     * 将 Java 字段名转换为数据库列名。
     *
     * @param fieldName Java 字段名
     * @return 数据库列名
     */
    String toColumnName(String fieldName);

    /**
     * 驼峰转下划线（默认策略）。
     * <p>例：userName -&gt; user_name，userID -&gt; user_i_d（连续大写逐字符插入下划线，
     * 不做缩写词特判——与 beacon-database 一致，保持转换规则确定性、可预测）。</p>
     */
    ColumnNamingStrategy CAMEL_TO_UNDERSCORE = fieldName -> {
        if (fieldName == null || fieldName.isEmpty()) {
            return fieldName;
        }
        StringBuilder result = new StringBuilder(fieldName.length() + 8);
        for (int i = 0; i < fieldName.length(); i++) {
            char c = fieldName.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    result.append('_');
                }
                result.append(Character.toLowerCase(c));
            } else {
                result.append(c);
            }
        }
        return result.toString();
    };

    /**
     * 保持原样：Java 字段名即数据库列名（如列名已是全小写场景，或消费方自定义完全接管）。
     */
    ColumnNamingStrategy IDENTITY = fieldName -> fieldName;
}
