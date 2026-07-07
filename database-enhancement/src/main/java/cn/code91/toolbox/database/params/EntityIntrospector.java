package cn.code91.toolbox.database.params;

import cn.code91.toolbox.database.annotation.Column;
import cn.code91.toolbox.database.annotation.Id;
import cn.code91.toolbox.database.annotation.Transient;
import cn.code91.toolbox.database.naming.ColumnNamingStrategy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Entity 元数据反射器</b>
 * <p>读取 {@link Column} / {@link Id} / {@link Transient} 注解，回退
 * {@link ColumnNamingStrategy} 推算 SQL 列名，构造 {@link EntityMeta}。
 * 默认命名策略路径经 {@link ClassValue} 缓存（per-class 计算一次，弱引用 classloader，无 leak）。</p>
 *
 * <p>反射安全：static 字段、{@code @Transient} 字段被跳过；遍历 superclass 字段（子类字段先，
 * 父类字段后——与 beacon-database 一致）。</p>
 */
public final class EntityIntrospector {

    /**
     * ClassValue 缓存（默认命名策略路径）。JDK 原生 per-class 派生数据 API：弱引用 entityClass，
     * 与 classloader 生命周期对齐无 leak；双重 lazy init 无锁路径，性能优于
     * {@code ConcurrentHashMap.computeIfAbsent}；per-class {@code computeValue} 只调一次。
     */
    private static final ClassValue<EntityMeta> CACHE = new ClassValue<>() {
        @Override
        protected EntityMeta computeValue(Class<?> type) {
            return introspect(type, ColumnNamingStrategy.CAMEL_TO_UNDERSCORE);
        }
    };

    private EntityIntrospector() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }

    /**
     * 获取 entity 元数据，使用默认 {@link ColumnNamingStrategy#CAMEL_TO_UNDERSCORE}。
     * <p>本方法走 {@link ClassValue} 缓存，per-class 计算一次。</p>
     */
    public static EntityMeta of(Class<?> entityClass) {
        return CACHE.get(entityClass);
    }

    /**
     * 获取 entity 元数据，使用自定义 {@link ColumnNamingStrategy}。
     * <p>本方法<b>不</b>走缓存（strategy 是参数，无法作为 ClassValue 的 key），每次调用重新反射。
     * 高频场景应使用单参版本（默认策略）。</p>
     */
    public static EntityMeta of(Class<?> entityClass, ColumnNamingStrategy strategy) {
        return introspect(entityClass, strategy);
    }

    private static EntityMeta introspect(Class<?> entityClass, ColumnNamingStrategy strategy) {
        List<FieldMapping> fields = new ArrayList<>();
        for (Class<?> c = entityClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (f.isAnnotationPresent(Transient.class)) {
                    continue;
                }
                f.setAccessible(true);
                Column columnAnnotation = f.getAnnotation(Column.class);
                String sqlColumn = (columnAnnotation != null && !columnAnnotation.value().isEmpty())
                        ? columnAnnotation.value()
                        : strategy.toColumnName(f.getName());
                boolean isId = f.isAnnotationPresent(Id.class);
                fields.add(new FieldMapping(f.getName(), sqlColumn, f, isId));
            }
        }
        return new EntityMeta(entityClass, List.copyOf(fields));
    }
}
