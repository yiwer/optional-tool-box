package cn.code91.toolbox.database.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link ColumnLayout}：{@code ResultSetMetaData} 列名提取（小写规整、重复列名报错）+
 * name→1-based index 映射，供 {@link EntityRowMapper} 按列布局缓存复用。
 */
@DisplayName("ColumnLayout")
class ColumnLayoutTest {

    @Test
    @DisplayName("extractNames: 列 label 小写规整")
    void extractLowercases() throws SQLException {
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(2);
        when(md.getColumnLabel(1)).thenReturn("ID");
        when(md.getColumnLabel(2)).thenReturn("User_Name");

        assertThat(ColumnLayout.extractNames(md)).containsExactly("id", "user_name");
    }

    @Test
    @DisplayName("extractNames: 重复列名（大小写不敏感）抛 SQLException")
    void extractDuplicateThrows() throws SQLException {
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(2);
        when(md.getColumnLabel(1)).thenReturn("a");
        when(md.getColumnLabel(2)).thenReturn("A");

        assertThatThrownBy(() -> ColumnLayout.extractNames(md))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    @DisplayName("fromNames: nameToIndex 为 1-based")
    void fromNamesIndex() {
        ColumnLayout layout = ColumnLayout.fromNames(List.of("id", "user_name"));

        assertThat(layout.indexOfColumn("id")).isEqualTo(1);
        assertThat(layout.indexOfColumn("user_name")).isEqualTo(2);
    }

    @Test
    @DisplayName("hasColumn/indexOfColumn: 入参大小写不敏感，未命中返回 -1")
    void hasAndIndexCaseInsensitive() {
        ColumnLayout layout = ColumnLayout.fromNames(List.of("id"));

        assertThat(layout.hasColumn("ID")).isTrue();
        assertThat(layout.hasColumn("missing")).isFalse();
        assertThat(layout.indexOfColumn("missing")).isEqualTo(-1);
    }

    @Test
    @DisplayName("乱序列：列布局支持任意列顺序（列名→下标映射不依赖物理顺序）")
    void supportsOutOfOrderColumns() {
        ColumnLayout layout = ColumnLayout.fromNames(List.of("email_addr", "id", "user_name"));

        assertThat(layout.indexOfColumn("id")).isEqualTo(2);
        assertThat(layout.indexOfColumn("user_name")).isEqualTo(3);
        assertThat(layout.indexOfColumn("email_addr")).isEqualTo(1);
    }

    @Test
    @DisplayName("列子集：仅部分列存在时 hasColumn 正确区分")
    void supportsColumnSubset() {
        ColumnLayout layout = ColumnLayout.fromNames(List.of("id", "user_name"));

        assertThat(layout.hasColumn("id")).isTrue();
        assertThat(layout.hasColumn("user_name")).isTrue();
        assertThat(layout.hasColumn("email_addr")).isFalse();
    }
}
