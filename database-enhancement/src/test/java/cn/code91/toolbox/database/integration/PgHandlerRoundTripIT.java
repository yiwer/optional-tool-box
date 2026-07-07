package cn.code91.toolbox.database.integration;

import cn.code91.toolbox.database.dialect.PostgresDialect;
import cn.code91.toolbox.database.dialect.handler.PgInetHandler;
import cn.code91.toolbox.database.dialect.handler.PgIntArrayHandler;
import cn.code91.toolbox.database.dialect.handler.PgJsonbHandler;
import cn.code91.toolbox.database.dialect.handler.PgTextArrayHandler;
import cn.code91.toolbox.database.dialect.handler.PgTimestamptzHandler;
import cn.code91.toolbox.database.dialect.handler.PgUuidHandler;
import cn.code91.toolbox.database.registry.FieldHandlerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.jdbc.support.SqlValue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>PG 特化 handler 真库读写往返（Testcontainers PostgreSQL）</b>
 * <p>覆盖 brief 明确列出的 PG 类型集：jsonb（对象↔JSON）、uuid、inet、text[]/int[] 数组、
 * timestamptz。每个 handler 独立开一张最小表，经 {@link PreparedStatement}/{@link ResultSet}
 * 直接调用 {@code write}/{@code read}，验证驱动往返（{@code PGobject}/{@code Array} 等驱动
 * 特化对象的真实序列化行为，不能仅靠 mock 断言）。</p>
 */
@DisplayName("PG 特化 handler 真库读写往返（IT）")
class PgHandlerRoundTripIT extends AbstractPostgresIntegrationTest {

    record Point(int x, int y) {
    }

    private static Connection connection;

    @BeforeAll
    static void openConnection() throws SQLException {
        connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS pg_handler_roundtrip (
                        id INT PRIMARY KEY,
                        jsonb_col JSONB,
                        uuid_col UUID,
                        inet_col INET,
                        text_array_col TEXT[],
                        int_array_col INTEGER[],
                        timestamptz_col TIMESTAMPTZ
                    )
                    """);
        }
    }

    @AfterAll
    static void closeConnection() throws SQLException {
        if (connection != null) {
            connection.close();
        }
    }

    @BeforeEach
    void truncate() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("TRUNCATE TABLE pg_handler_roundtrip");
        }
    }

    @Test
    @DisplayName("jsonb: POJO 写入后原样读回")
    void jsonbRoundTrips() throws SQLException {
        PgJsonbHandler<Point> handler = new PgJsonbHandler<>(Point.class);
        Point original = new Point(3, 4);

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO pg_handler_roundtrip (id, jsonb_col) VALUES (?, ?)")) {
            ps.setInt(1, 1);
            bindWriteResult(ps, 2, handler.write(original));
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT jsonb_col FROM pg_handler_roundtrip WHERE id = ?")) {
            ps.setInt(1, 1);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(handler.read(rs, 1)).isEqualTo(original);
            }
        }
    }

    @Test
    @DisplayName("uuid: 写入后原样读回")
    void uuidRoundTrips() throws SQLException {
        PgUuidHandler handler = new PgUuidHandler();
        UUID original = UUID.randomUUID();

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO pg_handler_roundtrip (id, uuid_col) VALUES (?, ?)")) {
            ps.setInt(1, 2);
            bindWriteResult(ps, 2, handler.write(original));
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid_col FROM pg_handler_roundtrip WHERE id = ?")) {
            ps.setInt(1, 2);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(handler.read(rs, 1)).isEqualTo(original);
            }
        }
    }

    @Test
    @DisplayName("inet: 写入后原样读回（PGobject 真实往返）")
    void inetRoundTrips() throws SQLException {
        PgInetHandler handler = new PgInetHandler();
        String original = "192.168.1.100";

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO pg_handler_roundtrip (id, inet_col) VALUES (?, ?)")) {
            ps.setInt(1, 3);
            bindWriteResult(ps, 2, handler.write(original));
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT inet_col FROM pg_handler_roundtrip WHERE id = ?")) {
            ps.setInt(1, 3);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(handler.read(rs, 1)).isEqualTo(original);
            }
        }
    }

    @Test
    @DisplayName("text[]: 写入后原样读回（真实驱动 Array 往返）")
    void textArrayRoundTrips() throws SQLException {
        PgTextArrayHandler handler = new PgTextArrayHandler();
        String[] original = {"alpha", "beta", "gamma"};

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO pg_handler_roundtrip (id, text_array_col) VALUES (?, ?)")) {
            ps.setInt(1, 4);
            bindWriteResult(ps, 2, handler.write(original));
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT text_array_col FROM pg_handler_roundtrip WHERE id = ?")) {
            ps.setInt(1, 4);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(handler.read(rs, 1)).containsExactly("alpha", "beta", "gamma");
            }
        }
    }

    @Test
    @DisplayName("int[]: 写入后原样读回（真实驱动 Array 往返，primitive 数组）")
    void intArrayRoundTrips() throws SQLException {
        PgIntArrayHandler handler = new PgIntArrayHandler();
        int[] original = {10, 20, 30};

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO pg_handler_roundtrip (id, int_array_col) VALUES (?, ?)")) {
            ps.setInt(1, 5);
            bindWriteResult(ps, 2, handler.write(original));
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT int_array_col FROM pg_handler_roundtrip WHERE id = ?")) {
            ps.setInt(1, 5);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(handler.read(rs, 1)).containsExactly(10, 20, 30);
            }
        }
    }

    @Test
    @DisplayName("timestamptz: 写入后原样读回（带时区偏移量）")
    void timestamptzRoundTrips() throws SQLException {
        PgTimestamptzHandler handler = new PgTimestamptzHandler();
        OffsetDateTime original = OffsetDateTime.of(2026, 7, 6, 10, 30, 0, 0, ZoneOffset.ofHours(8));

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO pg_handler_roundtrip (id, timestamptz_col) VALUES (?, ?)")) {
            ps.setInt(1, 6);
            bindWriteResult(ps, 2, handler.write(original));
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT timestamptz_col FROM pg_handler_roundtrip WHERE id = ?")) {
            ps.setInt(1, 6);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                OffsetDateTime readBack = handler.read(rs, 1);
                // PG timestamptz 内部按 UTC 存储，驱动读回的 offset 表示可能与写入时不同
                // 但绝对时刻相同——用 isEqual（瞬时点比较）而非 equals（要求 offset 也相同）。
                assertThat(readBack.isEqual(original)).as("%s should be the same instant as %s", readBack, original).isTrue();
            }
        }
    }

    @Test
    @DisplayName("PostgresDialect.registerBuiltins 装配出的 registry 也能完成同一 jsonb 往返")
    void registryWiredHandlerRoundTripsJsonb() throws SQLException {
        FieldHandlerRegistry registry = new FieldHandlerRegistry();
        new PostgresDialect().registerBuiltins(registry);
        @SuppressWarnings("unchecked")
        cn.code91.toolbox.database.spi.FieldHandler<Point> handler =
                (cn.code91.toolbox.database.spi.FieldHandler<Point>) registry
                        .findHandlerForSqlType("jsonb", Point.class).orElseThrow();
        Point original = new Point(7, 8);

        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO pg_handler_roundtrip (id, jsonb_col) VALUES (?, ?)")) {
            ps.setInt(1, 7);
            bindWriteResult(ps, 2, handler.write(original));
            ps.executeUpdate();
        }

        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT jsonb_col FROM pg_handler_roundtrip WHERE id = ?")) {
            ps.setInt(1, 7);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(handler.read(rs, 1)).isEqualTo(original);
            }
        }
    }

    /**
     * <b>{@code FieldHandler.write} 返回值的统一绑定方式</b>
     * <p>数组 handler（{@code PgTextArrayHandler}/{@code PgIntArrayHandler}）的 {@code write}
     * 返回 {@link SqlValue}——这是 Spring JDBC 的惰性求值载体（需要 {@code Connection} 才能
     * {@code createArrayOf}，而 handler 的 {@code write} 方法本身拿不到 connection）。正常业务
     * 场景下调用方是 Spring 的 {@code NamedParameterJdbcTemplate}（Task 8），其参数绑定内部
     * (StatementCreatorUtils) 已经对 {@code SqlValue} 做了特殊处理，业务代码不会直接摸到这层。
     * 本 IT 直接用裸 JDBC {@link PreparedStatement} 验证驱动往返（不经 Spring），故需要自己
     * 模拟这一步——{@code PreparedStatement.setObject} 遇到未知类型（如 {@code SqlValue}
     * 实现类）会直接抛 {@code PSQLException}（"Can't infer the SQL type"），这是本类第一版
     * 直接 {@code setObject(idx, handler.write(...))} 时的真实报错，特此记录以说明为何需要
     * 本方法而非直接调用 {@code setObject}。</p>
     */
    private static void bindWriteResult(PreparedStatement ps, int paramIndex, Object writeResult) throws SQLException {
        if (writeResult instanceof SqlValue sqlValue) {
            sqlValue.setValue(ps, paramIndex);
        } else {
            ps.setObject(paramIndex, writeResult);
        }
    }
}
