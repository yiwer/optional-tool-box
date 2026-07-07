package cn.code91.toolbox.database.mapper;

import cn.code91.toolbox.database.annotation.Column;
import cn.code91.toolbox.database.params.EntityIntrospector;
import cn.code91.toolbox.database.params.EntityMeta;
import cn.code91.toolbox.database.params.FieldMapping;
import cn.code91.toolbox.database.registry.FieldHandlerRegistry;
import cn.code91.toolbox.database.spi.FieldHandler;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>L2 {@link RowMapper} 实现</b>
 * <p>把 {@code ResultSet} 行映射为实体：按 {@link EntityIntrospector} 拿到字段发现结果，
 * 为每字段在构造期绑定 {@link FieldHandler}（read 路径严格——字段无 handler 覆盖直接在
 * <b>构造期</b> 抛 {@link IllegalStateException}，因为读必须知道怎么读列；与 L1
 * {@code AnnotatedParameterSource} 写路径的宽松回退是非对称设计，理由见类内注释）。</p>
 *
 * <p><b>列布局缓存</b>（通用设计基线）：实例级 {@code layoutCache} 按 {@link ColumnLayout}
 * 的完整列名序列（{@link ColumnLayout#extractNames}）为 key 缓存布局——同一 mapper 实例处理
 * "列顺序任意、可为列子集"的多次查询时，只要列名序列形状相同（如反复执行同一 SELECT），
 * 布局只计算一次，后续行直接复用（O(1) 查列下标，避免每行重新扫描 {@code ResultSetMetaData}）。
 * 缓存不跨 mapper 实例共享（每个 {@code EntityRowMapper} 实例独立一个缓存）。</p>
 *
 * @param <T> entity 类型
 */
public final class EntityRowMapper<T> implements RowMapper<T> {

    /** 单字段的读路径绑定：L1 字段映射 + 已解析好的 handler。 */
    private record BoundField(FieldMapping mapping, FieldHandler<?> handler) {
    }

    private final Class<T> entityClass;
    private final Constructor<T> constructor;
    private final List<BoundField> boundFields;
    private final ConcurrentHashMap<List<String>, ColumnLayout> layoutCache = new ConcurrentHashMap<>();

    /**
     * 构造期完成：no-arg constructor 解析 + 全部字段 handler 绑定（read 路径严格 fail-fast）。
     *
     * @throws IllegalStateException entity 缺少 no-arg constructor，或存在字段类型无 handler 覆盖
     */
    public EntityRowMapper(Class<T> entityClass, FieldHandlerRegistry registry) {
        this.entityClass = entityClass;
        this.constructor = resolveNoArgConstructor(entityClass);
        this.boundFields = bindFields(entityClass, registry);
    }

    @Override
    public T mapRow(ResultSet rs, int rowNum) throws SQLException {
        ColumnLayout layout = getOrBuildLayout(rs.getMetaData());
        T entity = instantiate();
        for (BoundField bf : boundFields) {
            String col = bf.mapping().sqlColumnName();
            if (!layout.hasColumn(col)) {
                continue;  // 列子集：本次查询未包含该列，跳过（保留字段默认值）
            }
            int idx = layout.indexOfColumn(col);
            Object value = readWithHandler(bf.handler(), rs, idx);
            Field field = bf.mapping().field();
            if (value == null && field.getType().isPrimitive()) {
                continue;  // primitive-null-skip：SQL NULL 对 primitive 字段无法 set，保留默认值
            }
            try {
                field.set(entity, value);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("无法写入字段 " + bf.mapping().javaFieldName(), e);
            }
        }
        return entity;
    }

    private ColumnLayout getOrBuildLayout(ResultSetMetaData md) throws SQLException {
        List<String> names = ColumnLayout.extractNames(md);
        ColumnLayout cached = layoutCache.get(names);
        if (cached != null) {
            return cached;
        }
        ColumnLayout built = ColumnLayout.fromNames(names);
        return layoutCache.computeIfAbsent(names, key -> built);
    }

    @SuppressWarnings("unchecked")
    private static Object readWithHandler(FieldHandler<?> handler, ResultSet rs, int columnIndex) throws SQLException {
        return ((FieldHandler<Object>) handler).read(rs, columnIndex);
    }

    private T instantiate() {
        try {
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法实例化 " + entityClass.getName(), e);
        }
    }

    private static <T> Constructor<T> resolveNoArgConstructor(Class<T> entityClass) {
        try {
            Constructor<T> ctor = entityClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "Entity " + entityClass.getName() + " 缺少 no-arg constructor（EntityRowMapper read 路径需要）", e);
        }
    }

    /**
     * 为每个可映射字段解析 handler：{@code @Column.sqlType} 非空则先按 alias 精确路由，
     * 否则按 Java 类型查找；两者均未命中 → 直接抛 ISE（read 路径严格，不像 L1 写路径宽松回退，
     * 因为读取时必须确切知道"怎么把 SQL 值转换为该字段类型"，裸值赋值可能类型不兼容而在
     * 运行期抛出更难定位的 {@code ClassCastException}）。
     */
    private static List<BoundField> bindFields(Class<?> entityClass, FieldHandlerRegistry registry) {
        EntityMeta meta = EntityIntrospector.of(entityClass);
        List<BoundField> bound = new ArrayList<>(meta.fields().size());
        for (FieldMapping fm : meta.fields()) {
            Field field = fm.field();
            Column columnAnnotation = field.getAnnotation(Column.class);
            String sqlType = (columnAnnotation != null) ? columnAnnotation.sqlType() : "";
            FieldHandler<?> handler = !sqlType.isEmpty()
                    ? registry.findHandlerForSqlType(sqlType, field.getType()).orElseThrow(() ->
                    new IllegalStateException("无 FieldHandler 处理 sqlType=" + sqlType + " 于 "
                            + entityClass.getSimpleName() + "#" + fm.javaFieldName()))
                    : registry.findHandler(field.getType()).orElseThrow(() ->
                    new IllegalStateException("无 FieldHandler 处理字段 "
                            + entityClass.getSimpleName() + "#" + fm.javaFieldName()
                            + "（类型 " + field.getType().getName() + "）"));
            bound.add(new BoundField(fm, handler));
        }
        return List.copyOf(bound);
    }
}
