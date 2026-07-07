package cn.code91.toolbox.database.mapper;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <b>ResultSet 列布局</b>
 * <p>列名小写规整 + name→1-based index 映射，支持任意列顺序与列子集（SELECT 列序与实体字段
 * 声明序无关，也可只查部分列）。重复列名（如 {@code SELECT a, a}）在 {@link #extractNames}
 * 抛 {@link SQLException} 以防隐藏 bug（同名列会导致映射歧义）。</p>
 *
 * <p>{@link EntityRowMapper} 按完整列名序列（{@link #extractNames} 的返回值）为 key 缓存
 * {@code ColumnLayout} 实例：同一形状的查询（列名序列完全一致）复用同一布局，避免每行重建
 * name→index 映射（通用设计基线"列布局缓存"）。</p>
 */
public record ColumnLayout(List<String> columnNames, Map<String, Integer> nameToIndex) {

    public static List<String> extractNames(ResultSetMetaData meta) throws SQLException {
        int count = meta.getColumnCount();
        List<String> names = new ArrayList<>(count);
        Set<String> seen = new HashSet<>(count * 2);
        for (int i = 1; i <= count; i++) {
            String label = meta.getColumnLabel(i);
            String name = (label != null ? label : meta.getColumnName(i)).toLowerCase();
            if (!seen.add(name)) {
                throw new SQLException("Duplicate column label '" + name + "' in query result set");
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    public static ColumnLayout fromNames(List<String> names) {
        Map<String, Integer> idx = new HashMap<>(names.size() * 2);
        for (int i = 0; i < names.size(); i++) {
            idx.put(names.get(i), i + 1);
        }
        return new ColumnLayout(names, Map.copyOf(idx));
    }

    public boolean hasColumn(String columnName) {
        return nameToIndex.containsKey(columnName.toLowerCase());
    }

    public int indexOfColumn(String columnName) {
        return nameToIndex.getOrDefault(columnName.toLowerCase(), -1);
    }
}
