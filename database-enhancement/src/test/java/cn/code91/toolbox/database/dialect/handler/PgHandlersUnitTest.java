package cn.code91.toolbox.database.dialect.handler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PGobject;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code dialect.handler} 子包内其余 PG 特化 handler 的纯逻辑单测（sqlTypeAliases、write 包装、
 * read 对 mock 驱动对象的处理）。真实驱动往返由 {@code PgHandlerRoundTripIT} 覆盖。
 */
@DisplayName("PG 特化 handler（inet/uuid/timestamptz/array）")
class PgHandlersUnitTest {

    @Nested
    @DisplayName("PgInetHandler")
    class PgInetHandlerTest {
        private final PgInetHandler handler = new PgInetHandler();

        @Test
        void javaTypeIsString() {
            assertThat(handler.javaType()).isEqualTo(String.class);
        }

        @Test
        void sqlTypeAliasesIncludeInetAndCidr() {
            assertThat(handler.sqlTypeAliases()).containsExactlyInAnyOrder("inet", "cidr");
        }

        @Test
        void writeWrapsValueAsInetPGobject() throws SQLException {
            Object written = handler.write("192.168.1.1");

            assertThat(written).isInstanceOf(PGobject.class);
            PGobject pgObject = (PGobject) written;
            assertThat(pgObject.getType()).isEqualTo("inet");
            assertThat(pgObject.getValue()).isEqualTo("192.168.1.1");
        }

        @Test
        void writeNullReturnsNull() throws SQLException {
            assertThat(handler.write(null)).isNull();
        }

        @Test
        void readExtractsValueFromPGobject() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            PGobject pgObject = new PGobject();
            pgObject.setType("inet");
            pgObject.setValue("10.0.0.1");
            when(rs.getObject(1, PGobject.class)).thenReturn(pgObject);

            assertThat(handler.read(rs, 1)).isEqualTo("10.0.0.1");
        }

        @Test
        void readNullColumnReturnsNull() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject(1, PGobject.class)).thenReturn(null);

            assertThat(handler.read(rs, 1)).isNull();
        }
    }

    @Nested
    @DisplayName("PgUuidHandler")
    class PgUuidHandlerTest {
        private final PgUuidHandler handler = new PgUuidHandler();

        @Test
        void javaTypeIsUuid() {
            assertThat(handler.javaType()).isEqualTo(UUID.class);
        }

        @Test
        void sqlTypeAliasesIncludeUuid() {
            assertThat(handler.sqlTypeAliases()).containsExactly("uuid");
        }

        @Test
        void writeReturnsValueAsIs() {
            UUID uuid = UUID.randomUUID();
            assertThat(handler.write(uuid)).isEqualTo(uuid);
        }

        @Test
        void readDelegatesToGetObject() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            UUID uuid = UUID.randomUUID();
            when(rs.getObject(1, UUID.class)).thenReturn(uuid);

            assertThat(handler.read(rs, 1)).isEqualTo(uuid);
        }
    }

    @Nested
    @DisplayName("PgTimestamptzHandler")
    class PgTimestamptzHandlerTest {
        private final PgTimestamptzHandler handler = new PgTimestamptzHandler();

        @Test
        void javaTypeIsOffsetDateTime() {
            assertThat(handler.javaType()).isEqualTo(OffsetDateTime.class);
        }

        @Test
        void sqlTypeAliasesIncludeTimestamptz() {
            assertThat(handler.sqlTypeAliases()).containsExactly("timestamptz");
        }

        @Test
        void writeReturnsValueAsIs() {
            OffsetDateTime odt = OffsetDateTime.now();
            assertThat(handler.write(odt)).isEqualTo(odt);
        }

        @Test
        void readDelegatesToGetObject() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            OffsetDateTime odt = OffsetDateTime.now();
            when(rs.getObject(1, OffsetDateTime.class)).thenReturn(odt);

            assertThat(handler.read(rs, 1)).isEqualTo(odt);
        }
    }

    @Nested
    @DisplayName("PgTextArrayHandler")
    class PgTextArrayHandlerTest {
        private final PgTextArrayHandler handler = new PgTextArrayHandler();

        @Test
        void javaTypeIsStringArray() {
            assertThat(handler.javaType()).isEqualTo(String[].class);
        }

        @Test
        void sqlTypeAliasesIncludeTextAndVarchar() {
            assertThat(handler.sqlTypeAliases()).containsExactlyInAnyOrder("text[]", "varchar[]");
        }

        @Test
        void writeNullReturnsNull() {
            assertThat(handler.write(null)).isNull();
        }

        @Test
        void writeNonNullReturnsSqlValueForLazyBinding() {
            Object written = handler.write(new String[]{"a", "b"});

            assertThat(written).isInstanceOf(org.springframework.jdbc.support.SqlValue.class);
        }

        @Test
        void readExtractsStringArrayFromDriverArray() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            Array array = mock(Array.class);
            when(array.getArray()).thenReturn(new String[]{"a", "b"});
            when(rs.getArray(1)).thenReturn(array);

            assertThat(handler.read(rs, 1)).containsExactly("a", "b");
        }

        @Test
        void readCoercesObjectArrayElementsToString() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            Array array = mock(Array.class);
            when(array.getArray()).thenReturn(new Object[]{"a", null});
            when(rs.getArray(1)).thenReturn(array);

            assertThat(handler.read(rs, 1)).containsExactly("a", null);
        }

        @Test
        void readNullArrayReturnsNull() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getArray(1)).thenReturn(null);

            assertThat(handler.read(rs, 1)).isNull();
        }
    }

    @Nested
    @DisplayName("PgIntArrayHandler")
    class PgIntArrayHandlerTest {
        private final PgIntArrayHandler handler = new PgIntArrayHandler();

        @Test
        void javaTypeIsIntArray() {
            assertThat(handler.javaType()).isEqualTo(int[].class);
        }

        @Test
        void sqlTypeAliasesIncludeIntVariants() {
            assertThat(handler.sqlTypeAliases()).containsExactlyInAnyOrder("int[]", "integer[]", "int4[]");
        }

        @Test
        void writeNullReturnsNull() {
            assertThat(handler.write(null)).isNull();
        }

        @Test
        void writeNonNullReturnsSqlValueForLazyBinding() {
            Object written = handler.write(new int[]{1, 2, 3});

            assertThat(written).isInstanceOf(org.springframework.jdbc.support.SqlValue.class);
        }

        @Test
        void readExtractsIntArrayFromDriverArray() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            Array array = mock(Array.class);
            when(array.getArray()).thenReturn(new int[]{1, 2, 3});
            when(rs.getArray(1)).thenReturn(array);

            assertThat(handler.read(rs, 1)).containsExactly(1, 2, 3);
        }

        @Test
        void readCoercesNumberObjectArrayToIntArray() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            Array array = mock(Array.class);
            when(array.getArray()).thenReturn(new Object[]{1, 2, 3});
            when(rs.getArray(1)).thenReturn(array);

            assertThat(handler.read(rs, 1)).containsExactly(1, 2, 3);
        }

        @Test
        void readNullArrayReturnsNull() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getArray(1)).thenReturn(null);

            assertThat(handler.read(rs, 1)).isNull();
        }
    }
}
