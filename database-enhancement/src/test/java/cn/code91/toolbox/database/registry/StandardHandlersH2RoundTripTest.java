package cn.code91.toolbox.database.registry;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>标准 handler 真实 JDBC 往返（H2，无需 Docker）</b>
 * <p>brief 明确允许"标准 handler 读写回环，用 H2 或纯对象"——{@code StandardHandlersTest}
 * 已用纯对象（mock {@link ResultSet}）覆盖各 handler 的独立逻辑；本类补充一个真实 JDBC
 * 连接的整体往返，验证 {@link FieldHandlerRegistry#registerBuiltins} 装配出的 handler 集
 * 在真实驱动类型转换（而非 mock 摆好的返回值）下仍然正确——这是 mock 测试无法覆盖的信号
 * （如 H2 驱动对 {@code getObject(idx, LocalDate.class)} 的真实实现行为）。PG 特化类型
 * （jsonb/inet/array/timestamptz）不在本类覆盖范围——H2 不支持这些 PG 专有类型，由
 * {@code PgHandlerRoundTripIT}（Testcontainers PostgreSQL）覆盖。</p>
 */
@DisplayName("标准 handler H2 真实 JDBC 往返")
class StandardHandlersH2RoundTripTest {

    private Connection connection;
    private final FieldHandlerRegistry registry = new FieldHandlerRegistry();

    @BeforeEach
    void setUp() throws SQLException {
        FieldHandlerRegistry.registerBuiltins(registry);
        // 每个测试方法用独立的内存库名（随机后缀），连接关闭即销毁——避免库跨方法存活
        // 导致第二个方法的 CREATE TABLE 撞已存在表（不用 DB_CLOSE_DELAY=-1）。
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:standard-handlers-roundtrip-" + UUID.randomUUID());
        connection = dataSource.getConnection();
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE standard_handlers_roundtrip (
                        id INT PRIMARY KEY,
                        s_val VARCHAR(255),
                        short_val SMALLINT,
                        int_val INT,
                        long_val BIGINT,
                        dec_val DECIMAL(10,2),
                        bool_val BOOLEAN,
                        date_val DATE,
                        datetime_val TIMESTAMP,
                        uuid_val UUID,
                        bytes_val VARBINARY(255)
                    )
                    """);
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    @DisplayName("registerBuiltins 装配出的全部标准 handler 真实往返一致")
    void allStandardHandlersRoundTripThroughRealJdbc() throws SQLException {
        String sVal = "hello";
        short shortVal = 7;
        int intVal = 42;
        long longVal = 123456789L;
        BigDecimal decVal = new BigDecimal("3.14");
        boolean boolVal = true;
        LocalDate dateVal = LocalDate.of(2026, 7, 6);
        LocalDateTime datetimeVal = LocalDateTime.of(2026, 7, 6, 10, 30);
        UUID uuidVal = UUID.randomUUID();
        byte[] bytesVal = {1, 2, 3, 4};

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO standard_handlers_roundtrip "
                        + "(id, s_val, short_val, int_val, long_val, dec_val, bool_val, date_val, datetime_val, uuid_val, bytes_val) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setInt(1, 1);
            ps.setObject(2, registry.findHandler(String.class).orElseThrow().write(sVal));
            ps.setObject(3, registry.findHandler(Short.class).orElseThrow().write(shortVal));
            ps.setObject(4, registry.findHandler(Integer.class).orElseThrow().write(intVal));
            ps.setObject(5, registry.findHandler(Long.class).orElseThrow().write(longVal));
            ps.setObject(6, registry.findHandler(BigDecimal.class).orElseThrow().write(decVal));
            ps.setObject(7, registry.findHandler(Boolean.class).orElseThrow().write(boolVal));
            ps.setObject(8, registry.findHandler(LocalDate.class).orElseThrow().write(dateVal));
            ps.setObject(9, registry.findHandler(LocalDateTime.class).orElseThrow().write(datetimeVal));
            ps.setObject(10, registry.findHandler(UUID.class).orElseThrow().write(uuidVal));
            ps.setObject(11, registry.findHandler(byte[].class).orElseThrow().write(bytesVal));
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT s_val, short_val, int_val, long_val, dec_val, bool_val, date_val, datetime_val, uuid_val, bytes_val "
                        + "FROM standard_handlers_roundtrip WHERE id = ?")) {
            ps.setInt(1, 1);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(registry.findHandler(String.class).orElseThrow().read(rs, 1)).isEqualTo(sVal);
                assertThat(registry.findHandler(Short.class).orElseThrow().read(rs, 2)).isEqualTo(shortVal);
                assertThat(registry.findHandler(Integer.class).orElseThrow().read(rs, 3)).isEqualTo(intVal);
                assertThat(registry.findHandler(Long.class).orElseThrow().read(rs, 4)).isEqualTo(longVal);
                assertThat(registry.findHandler(BigDecimal.class).orElseThrow().read(rs, 5)).isEqualByComparingTo(decVal);
                assertThat(registry.findHandler(Boolean.class).orElseThrow().read(rs, 6)).isEqualTo(boolVal);
                assertThat(registry.findHandler(LocalDate.class).orElseThrow().read(rs, 7)).isEqualTo(dateVal);
                assertThat(registry.findHandler(LocalDateTime.class).orElseThrow().read(rs, 8)).isEqualTo(datetimeVal);
                assertThat(registry.findHandler(UUID.class).orElseThrow().read(rs, 9)).isEqualTo(uuidVal);
                assertThat(registry.findHandler(byte[].class).orElseThrow().read(rs, 10)).isEqualTo(bytesVal);
            }
        }
    }

    @Test
    @DisplayName("枚举兜底 handler 经真实 JDBC 往返（按常量名存取）")
    void enumFallbackHandlerRoundTripsThroughRealJdbc() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE standard_handlers_roundtrip ADD COLUMN status_val VARCHAR(32)");
        }

        var handler = registry.findHandler(Status.class).orElseThrow();
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO standard_handlers_roundtrip (id, status_val) VALUES (?, ?)")) {
            ps.setInt(1, 2);
            ps.setObject(2, handler.write(Status.ARCHIVED));
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT status_val FROM standard_handlers_roundtrip WHERE id = ?")) {
            ps.setInt(1, 2);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(handler.read(rs, 1)).isEqualTo(Status.ARCHIVED);
            }
        }
    }

    enum Status {ACTIVE, ARCHIVED}
}
