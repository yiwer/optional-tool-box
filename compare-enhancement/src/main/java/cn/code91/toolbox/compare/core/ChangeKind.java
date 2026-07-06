package cn.code91.toolbox.compare.core;

/**
 * 字段变更的类型。
 */
public enum ChangeKind {
    /** 新增（旧值缺失，如集合新增元素）。 */
    ADDED,
    /** 移除（新值缺失，如集合减少元素）。 */
    REMOVED,
    /** 修改（新旧值均存在但不相等）。 */
    MODIFIED
}
