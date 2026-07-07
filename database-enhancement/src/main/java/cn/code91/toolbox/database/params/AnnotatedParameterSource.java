package cn.code91.toolbox.database.params;

import cn.code91.toolbox.database.annotation.Column;
import cn.code91.toolbox.database.naming.ColumnNamingStrategy;
import cn.code91.toolbox.database.registry.FieldHandlerRegistry;
import cn.code91.toolbox.database.spi.FieldHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.sql.SQLException;
import java.util.Optional;

/**
 * <b>L1 注解驱动的 ParameterSource 构造器</b>
 * <p>从 entity 反射读取全部非 {@code @Transient} 字段值，经 {@link FieldHandler#write} 转换
 * （无 handler 命中时宽松回退裸值，交给 Spring 默认绑定，见 {@link FieldHandlerRegistry} 类文档
 * "写路径宽松回退"），<b>同时以 Java 字段名与 SQL 列名两个 key 注册同一个值</b>（通用设计基线
 * "双 key"）：{@code :userName} 与 {@code :user_name} 均可作占位符命中同一值——手写 SQL
 * 与 {@code SqlBuilder} 生成 SQL（占位符用 SQL 列名）因此可以共用同一个
 * {@link SqlParameterSource} 实例，无需为两种 SQL 来源分别构造参数源。</p>
 */
public final class AnnotatedParameterSource {

    private AnnotatedParameterSource() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /** 使用空 registry（等价于"全部字段裸值绑定"）与默认命名策略构造。 */
    public static SqlParameterSource of(Object entity) {
        return of(entity, new FieldHandlerRegistry(), ColumnNamingStrategy.CAMEL_TO_UNDERSCORE);
    }

    /** 使用给定 registry（typically 装配出的已冻结 {@link FieldHandlerRegistry}）与默认命名策略。 */
    public static SqlParameterSource of(Object entity, FieldHandlerRegistry registry) {
        return of(entity, registry, ColumnNamingStrategy.CAMEL_TO_UNDERSCORE);
    }

    /** 使用给定命名策略与空 registry。 */
    public static SqlParameterSource of(Object entity, ColumnNamingStrategy strategy) {
        return of(entity, new FieldHandlerRegistry(), strategy);
    }

    /** 完整重载：显式指定 registry 与命名策略。 */
    public static SqlParameterSource of(Object entity, FieldHandlerRegistry registry, ColumnNamingStrategy strategy) {
        EntityMeta meta = (strategy == ColumnNamingStrategy.CAMEL_TO_UNDERSCORE)
                ? EntityIntrospector.of(entity.getClass())            // 走缓存
                : EntityIntrospector.of(entity.getClass(), strategy); // strategy 为参数，不走缓存
        MapSqlParameterSource ps = new MapSqlParameterSource();
        for (FieldMapping fm : meta.fields()) {
            Object raw;
            try {
                raw = fm.field().get(entity);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("无法读取字段 " + fm.javaFieldName(), e);
            }
            Column columnAnnotation = fm.field().getAnnotation(Column.class);
            String sqlType = (columnAnnotation != null) ? columnAnnotation.sqlType() : "";
            Object value = applyWrite(registry, fm.field().getType(), sqlType, raw);
            ps.addValue(fm.javaFieldName(), value);   // key 1：Java 字段名（如 :userName）
            ps.addValue(fm.sqlColumnName(), value);    // key 2：SQL 列名（如 :user_name，双 key）
        }
        return ps;
    }

    /**
     * 有 handler 则经 {@link FieldHandler#write} 转换；无则宽松回退裸值（不因未覆盖类型报错）。
     * {@link SQLException} 属 handler 内部转换失败（如 jsonb 序列化失败），非 JDBC 执行期异常，
     * 摊平为 {@link IllegalStateException}（配置/映射期失败，约束 3 允许的 fail-fast 形态）。
     *
     * <p>解析顺序（Task 7 接缝说明建议）：{@code sqlType} 非空 → 先按 alias 精确路由；否则/未命中
     * → 按 Java 类型查找；仍未命中 → 裸值。</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object applyWrite(FieldHandlerRegistry registry, Class<?> type, String sqlType, Object raw) {
        Optional<? extends FieldHandler<?>> handler = !sqlType.isEmpty()
                ? registry.findHandlerForSqlType(sqlType, (Class) type)
                : registry.findHandler((Class) type);
        if (handler.isEmpty()) {
            return raw;
        }
        try {
            return ((FieldHandler) handler.get()).write(raw);
        } catch (SQLException e) {
            throw new IllegalStateException("handler.write 失败（类型 " + type.getName() + "）", e);
        }
    }
}
