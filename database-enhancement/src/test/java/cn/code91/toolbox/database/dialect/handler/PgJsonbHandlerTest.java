package cn.code91.toolbox.database.dialect.handler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link PgJsonbHandler} 的纯逻辑验证（write 的 {@code PGobject} 包装、sqlTypeAliases、
 * read 对 mock {@link ResultSet} 的反序列化）。真实 jsonb 列的驱动往返由
 * {@code PgHandlerRoundTripIT} 覆盖。
 */
@DisplayName("PgJsonbHandler")
class PgJsonbHandlerTest {

    record Point(int x, int y) {
    }

    /** 模拟审计场景：含集合/Map 字段的 jsonb 载荷（空集合回归的最小夹具）。 */
    record AuditEntry(String action, Map<String, String> fieldChanges, List<String> tags) {
    }

    @Test
    @DisplayName("sqlTypeAliases 含 jsonb 与 json")
    void sqlTypeAliasesIncludeJsonbAndJson() {
        assertThat(new PgJsonbHandler<>(Point.class).sqlTypeAliases()).containsExactlyInAnyOrder("jsonb", "json");
    }

    @Test
    @DisplayName("write: POJO 序列化为 PGobject(type=jsonb)")
    void writeWrapsPojoAsJsonbPGobject() throws SQLException {
        PgJsonbHandler<Point> handler = new PgJsonbHandler<>(Point.class);

        Object written = handler.write(new Point(1, 2));

        assertThat(written).isInstanceOf(PGobject.class);
        PGobject pgObject = (PGobject) written;
        assertThat(pgObject.getType()).isEqualTo("jsonb");
        assertThat(pgObject.getValue()).contains("\"x\"").contains("1").contains("\"y\"").contains("2");
    }

    @Test
    @DisplayName("write: null 值返回 null（不构造 PGobject）")
    void writeNullReturnsNull() throws SQLException {
        PgJsonbHandler<Point> handler = new PgJsonbHandler<>(Point.class);

        assertThat(handler.write(null)).isNull();
    }

    @Test
    @DisplayName("read: JSON 字符串反序列化为目标 POJO")
    void readDeserializesJsonToPojo() throws SQLException {
        PgJsonbHandler<Point> handler = new PgJsonbHandler<>(Point.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("{\"x\":3,\"y\":4}");

        assertThat(handler.read(rs, 1)).isEqualTo(new Point(3, 4));
    }

    @Test
    @DisplayName("write: 空集合/空 Map 字段不得丢失（回归：canonical NON_EMPTY 曾把空 field_changes 整键丢掉）")
    void writePreservesEmptyCollectionFields() throws SQLException {
        PgJsonbHandler<AuditEntry> handler = new PgJsonbHandler<>(AuditEntry.class);

        PGobject written = (PGobject) handler.write(new AuditEntry("UPDATE", Map.of(), List.of()));

        // 键按字母序（canonical 保留 sortProperties），空集合必须显式落库为 {} / []，
        // 否则回读时缺键 → 字段为 null，空/null 语义丢失。
        assertThat(written.getValue())
                .isEqualTo("{\"action\":\"UPDATE\",\"fieldChanges\":{},\"tags\":[]}");
    }

    @Test
    @DisplayName("write→read 往返：空集合字段读回仍是空集合而非 null")
    void writeReadRoundTripPreservesEmptyCollections() throws SQLException {
        PgJsonbHandler<AuditEntry> handler = new PgJsonbHandler<>(AuditEntry.class);
        AuditEntry original = new AuditEntry("UPDATE", Map.of(), List.of());

        PGobject written = (PGobject) handler.write(original);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(written.getValue());

        AuditEntry readBack = handler.read(rs, 1);

        assertThat(readBack).as("空集合字段经落库往返后必须保真（不得退化为 null）").isEqualTo(original);
        assertThat(readBack.fieldChanges()).isNotNull().isEmpty();
        assertThat(readBack.tags()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("read: 列值为 null 时返回 null")
    void readNullColumnReturnsNull() throws SQLException {
        PgJsonbHandler<Point> handler = new PgJsonbHandler<>(Point.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(null);
        when(rs.wasNull()).thenReturn(true);

        assertThat(handler.read(rs, 1)).isNull();
    }

    @Test
    @DisplayName("read: 非法 JSON 抛 SQLException（不是 Result 或未包裹的 Jackson 异常）")
    void readInvalidJsonThrowsSqlException() throws SQLException {
        PgJsonbHandler<Point> handler = new PgJsonbHandler<>(Point.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("not-json");

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.read(rs, 1))
                .isInstanceOf(SQLException.class);
    }
}
