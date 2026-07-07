package cn.code91.toolbox.database.registry.handler;

import cn.code91.toolbox.database.spi.FieldHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 标准 handler 读写回环：write 返回值经 mock {@link ResultSet} 回读，验证 javaType 与
 * read/write 语义正确（brief 要求"标准 handler 读写回环，用 H2 或纯对象"——此处用纯对象
 * mock 验证 handler 自身逻辑；H2 层的真实 JDBC round trip 由 registry 装配测试与 IT 覆盖）。
 */
@DisplayName("标准 FieldHandler 读写回环")
class StandardHandlersTest {

    @Nested
    class StringHandlerTest {
        private final FieldHandler<String> handler = new StringHandler();

        @Test
        void javaTypeIsString() {
            assertThat(handler.javaType()).isEqualTo(String.class);
        }

        @Test
        void writeReturnsValueAsIs() throws SQLException {
            assertThat(handler.write("hello")).isEqualTo("hello");
        }

        @Test
        void readDelegatesToResultSetGetString() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(1)).thenReturn("hello");

            assertThat(handler.read(rs, 1)).isEqualTo("hello");
        }
    }

    @Nested
    class ShortHandlerTest {
        private final FieldHandler<Short> handler = new ShortHandler();

        @Test
        void javaTypeIsShort() {
            assertThat(handler.javaType()).isEqualTo(Short.class);
        }

        @Test
        void writeReturnsValueAsIs() throws SQLException {
            assertThat(handler.write((short) 7)).isEqualTo((short) 7);
        }

        @Test
        void readCoercesNumberToShort() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject(1)).thenReturn(7);

            assertThat(handler.read(rs, 1)).isEqualTo((short) 7);
        }

        @Test
        void readReturnsNullWhenColumnIsNull() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject(1)).thenReturn(null);

            assertThat(handler.read(rs, 1)).isNull();
        }
    }

    @Nested
    class IntegerHandlerTest {
        private final FieldHandler<Integer> handler = new IntegerHandler();

        @Test
        void javaTypeIsInteger() {
            assertThat(handler.javaType()).isEqualTo(Integer.class);
        }

        @Test
        void writeReturnsValueAsIs() throws SQLException {
            assertThat(handler.write(42)).isEqualTo(42);
        }

        @Test
        void readCoercesNumberToInteger() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject(1)).thenReturn(42L);

            assertThat(handler.read(rs, 1)).isEqualTo(42);
        }
    }

    @Nested
    class LongHandlerTest {
        private final FieldHandler<Long> handler = new LongHandler();

        @Test
        void javaTypeIsLong() {
            assertThat(handler.javaType()).isEqualTo(Long.class);
        }

        @Test
        void writeReturnsValueAsIs() throws SQLException {
            assertThat(handler.write(42L)).isEqualTo(42L);
        }

        @Test
        void readCoercesNumberToLong() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject(1)).thenReturn(42);

            assertThat(handler.read(rs, 1)).isEqualTo(42L);
        }
    }

    @Nested
    class BigDecimalHandlerTest {
        private final FieldHandler<BigDecimal> handler = new BigDecimalHandler();

        @Test
        void javaTypeIsBigDecimal() {
            assertThat(handler.javaType()).isEqualTo(BigDecimal.class);
        }

        @Test
        void writeReturnsValueAsIs() throws SQLException {
            assertThat(handler.write(new BigDecimal("3.14"))).isEqualTo(new BigDecimal("3.14"));
        }

        @Test
        void readDelegatesToResultSetGetBigDecimal() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getBigDecimal(1)).thenReturn(new BigDecimal("3.14"));

            assertThat(handler.read(rs, 1)).isEqualTo(new BigDecimal("3.14"));
        }
    }

    @Nested
    class BooleanHandlerTest {
        private final FieldHandler<Boolean> handler = new BooleanHandler();

        @Test
        void javaTypeIsBoolean() {
            assertThat(handler.javaType()).isEqualTo(Boolean.class);
        }

        @Test
        void writeReturnsValueAsIs() throws SQLException {
            assertThat(handler.write(true)).isEqualTo(true);
        }

        @Test
        void readCoercesObjectToBoolean() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getObject(1)).thenReturn(true);

            assertThat(handler.read(rs, 1)).isTrue();
        }
    }

    @Nested
    class LocalDateHandlerTest {
        private final FieldHandler<LocalDate> handler = new LocalDateHandler();

        @Test
        void javaTypeIsLocalDate() {
            assertThat(handler.javaType()).isEqualTo(LocalDate.class);
        }

        @Test
        void writeReturnsValueAsIs() throws SQLException {
            LocalDate date = LocalDate.of(2026, 7, 6);
            assertThat(handler.write(date)).isEqualTo(date);
        }

        @Test
        void readDelegatesToResultSetGetObject() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            LocalDate date = LocalDate.of(2026, 7, 6);
            when(rs.getObject(1, LocalDate.class)).thenReturn(date);

            assertThat(handler.read(rs, 1)).isEqualTo(date);
        }
    }

    @Nested
    class LocalDateTimeHandlerTest {
        private final FieldHandler<LocalDateTime> handler = new LocalDateTimeHandler();

        @Test
        void javaTypeIsLocalDateTime() {
            assertThat(handler.javaType()).isEqualTo(LocalDateTime.class);
        }

        @Test
        void writeReturnsValueAsIs() throws SQLException {
            LocalDateTime dt = LocalDateTime.of(2026, 7, 6, 10, 0);
            assertThat(handler.write(dt)).isEqualTo(dt);
        }

        @Test
        void readDelegatesToResultSetGetObject() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            LocalDateTime dt = LocalDateTime.of(2026, 7, 6, 10, 0);
            when(rs.getObject(1, LocalDateTime.class)).thenReturn(dt);

            assertThat(handler.read(rs, 1)).isEqualTo(dt);
        }
    }

    @Nested
    class OffsetDateTimeHandlerTest {
        private final FieldHandler<OffsetDateTime> handler = new OffsetDateTimeHandler();

        @Test
        void javaTypeIsOffsetDateTime() {
            assertThat(handler.javaType()).isEqualTo(OffsetDateTime.class);
        }

        @Test
        void writeReturnsValueAsIs() throws SQLException {
            OffsetDateTime odt = OffsetDateTime.now();
            assertThat(handler.write(odt)).isEqualTo(odt);
        }

        @Test
        void readDelegatesToResultSetGetObject() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            OffsetDateTime odt = OffsetDateTime.now();
            when(rs.getObject(1, OffsetDateTime.class)).thenReturn(odt);

            assertThat(handler.read(rs, 1)).isEqualTo(odt);
        }
    }

    @Nested
    class UuidHandlerTest {
        private final FieldHandler<UUID> handler = new UuidHandler();

        @Test
        void javaTypeIsUuid() {
            assertThat(handler.javaType()).isEqualTo(UUID.class);
        }

        @Test
        void writeReturnsValueAsIs() throws SQLException {
            UUID uuid = UUID.randomUUID();
            assertThat(handler.write(uuid)).isEqualTo(uuid);
        }

        @Test
        void readDelegatesToResultSetGetObject() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            UUID uuid = UUID.randomUUID();
            when(rs.getObject(1, UUID.class)).thenReturn(uuid);

            assertThat(handler.read(rs, 1)).isEqualTo(uuid);
        }
    }

    @Nested
    class ByteArrayHandlerTest {
        private final FieldHandler<byte[]> handler = new ByteArrayHandler();

        @Test
        void javaTypeIsByteArray() {
            assertThat(handler.javaType()).isEqualTo(byte[].class);
        }

        @Test
        void writeReturnsValueAsIs() throws SQLException {
            byte[] bytes = {1, 2, 3};
            assertThat(handler.write(bytes)).isEqualTo(bytes);
        }

        @Test
        void readDelegatesToResultSetGetBytes() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            byte[] bytes = {1, 2, 3};
            when(rs.getBytes(1)).thenReturn(bytes);

            assertThat(handler.read(rs, 1)).isEqualTo(bytes);
        }
    }

    @Nested
    class EnumByNameHandlerTest {

        enum Status {ACTIVE, ARCHIVED}

        private final FieldHandler<Status> handler = new EnumByNameHandler<>(Status.class);

        @Test
        void javaTypeIsEnumType() {
            assertThat(handler.javaType()).isEqualTo(Status.class);
        }

        @Test
        void writeReturnsEnumName() throws SQLException {
            assertThat(handler.write(Status.ACTIVE)).isEqualTo("ACTIVE");
        }

        @Test
        void writeNullReturnsNull() throws SQLException {
            assertThat(handler.write(null)).isNull();
        }

        @Test
        void readParsesEnumByName() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(1)).thenReturn("ARCHIVED");

            assertThat(handler.read(rs, 1)).isEqualTo(Status.ARCHIVED);
        }

        @Test
        void readNullColumnReturnsNull() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(1)).thenReturn(null);
            when(rs.wasNull()).thenReturn(true);

            assertThat(handler.read(rs, 1)).isNull();
        }

        @Test
        void readUnknownConstantThrowsSqlException() throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            when(rs.getString(1)).thenReturn("NOT_A_CONSTANT");

            org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.read(rs, 1))
                    .isInstanceOf(SQLException.class);
        }
    }
}
