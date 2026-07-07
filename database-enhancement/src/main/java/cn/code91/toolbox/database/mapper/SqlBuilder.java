package cn.code91.toolbox.database.mapper;

import cn.code91.toolbox.database.annotation.Table;
import cn.code91.toolbox.database.naming.ColumnNamingStrategy;
import cn.code91.toolbox.database.params.EntityIntrospector;
import cn.code91.toolbox.database.params.EntityMeta;
import cn.code91.toolbox.database.params.FieldMapping;

import java.util.EnumMap;
import java.util.List;

/**
 * <b>L2 SqlBuilder</b>
 * <p>从 L1 {@link EntityMeta} 生成四个 by-id CRUD SQL 文本。占位符用 SQL 列名（与
 * {@link cn.code91.toolbox.database.params.AnnotatedParameterSource} 双 key 的列名侧对齐，
 * 生成 SQL 与手写 SQL 可共用同一参数源）。表名解析：{@code @Table.value} 优先，否则类名经
 * {@link ColumnNamingStrategy#CAMEL_TO_UNDERSCORE} 推算。</p>
 *
 * <p>{@link ClassValue} 缓存 per-class {@code EnumMap<OpKind, String>}：同一 entity 反复调用
 * 返回同一 String 引用（identity-stable）。</p>
 *
 * <p>错误：0 {@code @Id} + updateById/selectById/deleteById → ISE；≥2 {@code @Id}（复合主键
 * 不支持）+ 任意 op → ISE；0 mappable fields（全 {@code @Transient}）+ 任意 op → ISE；
 * {@code @Table.value} 为空串 → ISE（均为配置期校验 fail-fast，约束 3 允许的形态）。</p>
 */
public final class SqlBuilder {

    private static final ColumnNamingStrategy NAMING = ColumnNamingStrategy.CAMEL_TO_UNDERSCORE;

    private static final ClassValue<EnumMap<OpKind, String>> CACHE = new ClassValue<>() {
        @Override
        protected EnumMap<OpKind, String> computeValue(Class<?> type) {
            return new EnumMap<>(OpKind.class);
        }
    };

    private SqlBuilder() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    public static String insert(Class<?> entityClass) {
        return sqlFor(entityClass, OpKind.INSERT);
    }

    public static String updateById(Class<?> entityClass) {
        return sqlFor(entityClass, OpKind.UPDATE_BY_ID);
    }

    public static String selectById(Class<?> entityClass) {
        return sqlFor(entityClass, OpKind.SELECT_BY_ID);
    }

    public static String deleteById(Class<?> entityClass) {
        return sqlFor(entityClass, OpKind.DELETE_BY_ID);
    }

    private static String sqlFor(Class<?> entityClass, OpKind op) {
        EnumMap<OpKind, String> slots = CACHE.get(entityClass);
        synchronized (slots) {                                  // EnumMap 非线程安全
            String cached = slots.get(op);
            if (cached != null) {
                return cached;
            }
            String built = build(entityClass, op);
            slots.put(op, built);
            return built;
        }
    }

    private static String build(Class<?> entityClass, OpKind op) {
        EntityMeta meta = EntityIntrospector.of(entityClass);
        List<FieldMapping> fields = meta.fields();
        if (fields.isEmpty()) {
            throw new IllegalStateException(
                    "entity " + entityClass.getName() + " has 0 mappable fields (全 @Transient 或 0 非 static 字段); op " + op + " 无法生成 SQL");
        }
        List<FieldMapping> idFields = fields.stream().filter(FieldMapping::isId).toList();
        if (idFields.size() > 1) {
            throw new IllegalStateException(
                    "entity " + entityClass.getName() + " has " + idFields.size()
                            + " @Id fields (复合主键不支持，仅单 @Id)");
        }
        if (op != OpKind.INSERT && idFields.isEmpty()) {
            throw new IllegalStateException(
                    "entity " + entityClass.getName() + " has no @Id field, op " + op + " requires @Id");
        }
        String table = resolveTableName(entityClass);
        return switch (op) {
            case INSERT -> buildInsert(table, fields);
            case UPDATE_BY_ID -> buildUpdateById(table, fields, idFields.get(0));
            case SELECT_BY_ID -> buildSelectById(table, fields, idFields.get(0));
            case DELETE_BY_ID -> buildDeleteById(table, idFields.get(0));
        };
    }

    private static String resolveTableName(Class<?> entityClass) {
        Table ann = entityClass.getAnnotation(Table.class);
        if (ann != null) {
            String v = ann.value();
            if (v.isEmpty()) {
                throw new IllegalStateException(
                        "@Table.value must be non-empty on " + entityClass.getName());
            }
            return v;
        }
        return NAMING.toColumnName(entityClass.getSimpleName());
    }

    private static String buildInsert(String table, List<FieldMapping> fields) {
        StringBuilder cols = new StringBuilder();
        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                cols.append(", ");
                placeholders.append(", ");
            }
            String col = fields.get(i).sqlColumnName();
            cols.append(col);
            placeholders.append(':').append(col);
        }
        return "INSERT INTO " + table + " (" + cols + ") VALUES (" + placeholders + ")";
    }

    private static String buildUpdateById(String table, List<FieldMapping> fields, FieldMapping idField) {
        StringBuilder set = new StringBuilder();
        boolean first = true;
        for (FieldMapping fm : fields) {
            if (fm.isId()) {
                continue;
            }
            if (!first) {
                set.append(", ");
            }
            first = false;
            String col = fm.sqlColumnName();
            set.append(col).append(" = :").append(col);
        }
        String idCol = idField.sqlColumnName();
        return "UPDATE " + table + " SET " + set + " WHERE " + idCol + " = :" + idCol;
    }

    private static String buildSelectById(String table, List<FieldMapping> fields, FieldMapping idField) {
        StringBuilder cols = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                cols.append(", ");
            }
            cols.append(fields.get(i).sqlColumnName());
        }
        String idCol = idField.sqlColumnName();
        return "SELECT " + cols + " FROM " + table + " WHERE " + idCol + " = :" + idCol;
    }

    private static String buildDeleteById(String table, FieldMapping idField) {
        String idCol = idField.sqlColumnName();
        return "DELETE FROM " + table + " WHERE " + idCol + " = :" + idCol;
    }
}
