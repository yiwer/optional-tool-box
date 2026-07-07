package cn.code91.toolbox.database.params;

import java.util.List;

/**
 * <b>缓存的 entity 元数据</b>
 * <p>每个 entity class 对应一个不可变 {@code EntityMeta}，由 {@link EntityIntrospector}
 * 经 {@link ClassValue} 缓存（默认命名策略路径）。</p>
 *
 * @param entityClass entity Java class
 * @param fields      字段映射列表（顺序与反射 {@code getDeclaredFields} 一致；含父类字段）
 */
public record EntityMeta(Class<?> entityClass, List<FieldMapping> fields) {
}
