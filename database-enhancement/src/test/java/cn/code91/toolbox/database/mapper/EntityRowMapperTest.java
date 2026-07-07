package cn.code91.toolbox.database.mapper;

import cn.code91.toolbox.database.annotation.Column;
import cn.code91.toolbox.database.registry.FieldHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link EntityRowMapper}：{@code ResultSet} → entity 映射（经 {@code FieldHandler.read}），
 * 列布局按 {@code ResultSetMetaData} 缓存（乱序列、列子集正确映射、相同形状复用）。
 */
@DisplayName("EntityRowMapper")
class EntityRowMapperTest {

    static class User {
        Long id;
        String userName;
        int score;  // primitive

        User() {
        }
    }

    private final FieldHandlerRegistry registry = defaultsRegistry();

    private static FieldHandlerRegistry defaultsRegistry() {
        FieldHandlerRegistry r = new FieldHandlerRegistry();
        FieldHandlerRegistry.registerBuiltins(r);
        return r;
    }

    private ResultSet mockRow(List3Cols cols) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(3);
        when(md.getColumnLabel(1)).thenReturn(cols.col1Name());
        when(md.getColumnLabel(2)).thenReturn(cols.col2Name());
        when(md.getColumnLabel(3)).thenReturn(cols.col3Name());
        when(rs.getMetaData()).thenReturn(md);
        return rs;
    }

    /** 帮助构造不同列名排列顺序的 mock ResultSetMetaData。 */
    private record List3Cols(String col1Name, String col2Name, String col3Name) {
    }

    @Test
    @DisplayName("mapRow: 标准列顺序（id, user_name, score）→ 字段映射")
    void mapsRowInDeclaredOrder() throws SQLException {
        ResultSet rs = mockRow(new List3Cols("id", "user_name", "score"));
        when(rs.getObject(1)).thenReturn(1L);
        when(rs.getString(2)).thenReturn("alice");
        when(rs.getObject(3)).thenReturn(10);

        EntityRowMapper<User> mapper = new EntityRowMapper<>(User.class, registry);
        User u = mapper.mapRow(rs, 0);

        assertThat(u.id).isEqualTo(1L);
        assertThat(u.userName).isEqualTo("alice");
        assertThat(u.score).isEqualTo(10);
    }

    @Test
    @DisplayName("mapRow: 乱序列（score, id, user_name）仍正确映射到对应字段")
    void mapsRowWithOutOfOrderColumns() throws SQLException {
        ResultSet rs = mockRow(new List3Cols("score", "id", "user_name"));
        when(rs.getObject(1)).thenReturn(20);       // score at index 1
        when(rs.getObject(2)).thenReturn(2L);        // id at index 2
        when(rs.getString(3)).thenReturn("bob");     // user_name at index 3

        EntityRowMapper<User> mapper = new EntityRowMapper<>(User.class, registry);
        User u = mapper.mapRow(rs, 0);

        assertThat(u.id).isEqualTo(2L);
        assertThat(u.userName).isEqualTo("bob");
        assertThat(u.score).isEqualTo(20);
    }

    @Test
    @DisplayName("mapRow: 列子集（仅 id, user_name，无 score 列）→ score 保留字段默认值")
    void mapsRowWithColumnSubset() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(2);
        when(md.getColumnLabel(1)).thenReturn("id");
        when(md.getColumnLabel(2)).thenReturn("user_name");
        when(rs.getMetaData()).thenReturn(md);
        when(rs.getObject(1)).thenReturn(3L);
        when(rs.getString(2)).thenReturn("carol");

        EntityRowMapper<User> mapper = new EntityRowMapper<>(User.class, registry);
        User u = mapper.mapRow(rs, 0);

        assertThat(u.id).isEqualTo(3L);
        assertThat(u.userName).isEqualTo("carol");
        assertThat(u.score).isEqualTo(0);  // 未映射的 primitive 字段保留默认值
    }

    @Test
    @DisplayName("mapRow: primitive 字段 SQL NULL → 跳过 set（保留默认值 0）")
    void primitiveNullSkipped() throws SQLException {
        ResultSet rs = mockRow(new List3Cols("id", "user_name", "score"));
        when(rs.getObject(1)).thenReturn(1L);
        when(rs.getString(2)).thenReturn("alice");
        when(rs.getObject(3)).thenReturn(null);

        EntityRowMapper<User> mapper = new EntityRowMapper<>(User.class, registry);
        User u = mapper.mapRow(rs, 0);

        assertThat(u.score).isEqualTo(0);
    }

    @Test
    @DisplayName("mapRow: object 字段 SQL NULL → set null")
    void objectFieldNullSet() throws SQLException {
        ResultSet rs = mockRow(new List3Cols("id", "user_name", "score"));
        when(rs.getObject(1)).thenReturn(null);
        when(rs.getString(2)).thenReturn(null);
        when(rs.getObject(3)).thenReturn(5);

        EntityRowMapper<User> mapper = new EntityRowMapper<>(User.class, registry);
        User u = mapper.mapRow(rs, 0);

        assertThat(u.id).isNull();
        assertThat(u.userName).isNull();
    }

    @Test
    @DisplayName("layoutCache: 相同列名序列的连续调用不抛错（复用同一布局）")
    void reusesLayoutForSameColumnShape() throws SQLException {
        EntityRowMapper<User> mapper = new EntityRowMapper<>(User.class, registry);

        ResultSet row1 = mockRow(new List3Cols("id", "user_name", "score"));
        when(row1.getObject(1)).thenReturn(1L);
        when(row1.getString(2)).thenReturn("a");
        when(row1.getObject(3)).thenReturn(1);

        ResultSet row2 = mockRow(new List3Cols("id", "user_name", "score"));
        when(row2.getObject(1)).thenReturn(2L);
        when(row2.getString(2)).thenReturn("b");
        when(row2.getObject(3)).thenReturn(2);

        User u1 = mapper.mapRow(row1, 0);
        User u2 = mapper.mapRow(row2, 1);

        assertThat(u1.id).isEqualTo(1L);
        assertThat(u2.id).isEqualTo(2L);
    }

    static class NoArgLessEntity {
        String x;

        NoArgLessEntity(String forced) {
            this.x = forced;
        }
    }

    @Test
    @DisplayName("entity 缺少 no-arg constructor → 构造 EntityRowMapper 时抛 ISE")
    void missingNoArgConstructorThrowsAtConstructionTime() {
        assertThatThrownBy(() -> new EntityRowMapper<>(NoArgLessEntity.class, registry))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-arg constructor");
    }

    static class UnhandledFieldEntity {
        Object anything;  // Object 类型无 handler 覆盖
    }

    @Test
    @DisplayName("字段类型无 handler 覆盖 → 构造 EntityRowMapper 时抛 ISE（read 路径严格）")
    void unhandledFieldTypeThrowsAtConstructionTime() {
        assertThatThrownBy(() -> new EntityRowMapper<>(UnhandledFieldEntity.class, registry))
                .isInstanceOf(IllegalStateException.class);
    }

    static class InetLikeEntity {
        @Column(value = "ip", sqlType = "custom-alias")
        String ip;
    }

    @Test
    @DisplayName("@Column(sqlType=...) 路由到 alias handler 的字段可正常读取")
    void sqlTypeRoutedFieldReadsViaAliasHandler() throws SQLException {
        FieldHandlerRegistry customRegistry = defaultsRegistry();
        customRegistry.registerFactory("custom-alias", type -> new cn.code91.toolbox.database.spi.FieldHandler<String>() {
            @Override
            public Class<String> javaType() {
                return String.class;
            }

            @Override
            public String read(ResultSet rs, int columnIndex) throws SQLException {
                return "aliased:" + rs.getString(columnIndex);
            }

            @Override
            public Object write(String value) {
                return value;
            }
        });

        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(md.getColumnCount()).thenReturn(1);
        when(md.getColumnLabel(1)).thenReturn("ip");
        when(rs.getMetaData()).thenReturn(md);
        when(rs.getString(1)).thenReturn("10.0.0.1");

        EntityRowMapper<InetLikeEntity> mapper = new EntityRowMapper<>(InetLikeEntity.class, customRegistry);
        InetLikeEntity entity = mapper.mapRow(rs, 0);

        assertThat(entity.ip).isEqualTo("aliased:10.0.0.1");
    }
}
