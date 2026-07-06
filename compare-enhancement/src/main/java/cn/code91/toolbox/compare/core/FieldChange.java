package cn.code91.toolbox.compare.core;

/**
 * 单个字段（或集合元素）的变更记录。
 *
 * @param path    字段路径，形如 {@code "amount"}、{@code "address.city"}、{@code "items[2].price"}
 * @param label   展示标签（{@code @CompareLabel} 解析结果，未标注时回退字段名）
 * @param kind    变更类型
 * @param oldValue 旧值（原始对象，未格式化）
 * @param newValue 新值（原始对象，未格式化）
 * @param oldText  旧值的展示文本（已格式化）
 * @param newText  新值的展示文本（已格式化）
 */
public record FieldChange(String path, String label, ChangeKind kind,
                           Object oldValue, Object newValue,
                           String oldText, String newText) {
}
