package cn.code91.toolbox.database.params;

import cn.code91.toolbox.database.annotation.Column;
import cn.code91.toolbox.database.annotation.Id;
import cn.code91.toolbox.database.annotation.Transient;
import cn.code91.toolbox.database.naming.ColumnNamingStrategy;
import cn.code91.toolbox.database.registry.FieldHandlerRegistry;
import cn.code91.toolbox.database.spi.FieldHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AnnotatedParameterSource} L1 参数源：双 key（Java 字段名 + SQL 列名）、
 * {@code @Transient} 跳过、handler write 生效、无 handler 宽松回退裸值。
 */
@DisplayName("AnnotatedParameterSource - 注解驱动 ParameterSource (L1)")
class AnnotatedParameterSourceTest {

    static class User {
        @Id
        Long id;
        String userName;
        @Column("created_ts")
        LocalDateTime createdAt;
        @Transient
        String displayName;

        User(Long id, String userName, LocalDateTime createdAt, String displayName) {
            this.id = id;
            this.userName = userName;
            this.createdAt = createdAt;
            this.displayName = displayName;
        }
    }

    @Test
    @DisplayName("of(entity): 双 key——Java 字段名 :userName 命中")
    void javaFieldNameKeyHits() {
        User user = new User(1L, "alice", LocalDateTime.parse("2026-05-22T10:00:00"), "ignored");
        SqlParameterSource ps = AnnotatedParameterSource.of(user);

        assertThat(ps.hasValue("userName")).isTrue();
        assertThat(ps.getValue("userName")).isEqualTo("alice");
    }

    @Test
    @DisplayName("of(entity): 双 key——SQL 列名 :user_name 与 :userName 命中同值")
    void sqlColumnNameKeyHitsSameValue() {
        User user = new User(1L, "alice", LocalDateTime.parse("2026-05-22T10:00:00"), "ignored");
        SqlParameterSource ps = AnnotatedParameterSource.of(user);

        assertThat(ps.hasValue("user_name")).isTrue();
        assertThat(ps.getValue("user_name")).isEqualTo(ps.getValue("userName"));
    }

    @Test
    @DisplayName("of(entity): @Column 显式列名同样双 key——createdAt 与 created_ts 均命中")
    void explicitColumnNameDualKey() {
        User user = new User(4L, "dave", LocalDateTime.parse("2026-05-22T12:00:00"), null);
        SqlParameterSource ps = AnnotatedParameterSource.of(user);

        assertThat(ps.hasValue("createdAt")).isTrue();
        assertThat(ps.hasValue("created_ts")).isTrue();
        assertThat(ps.getValue("createdAt")).isEqualTo(ps.getValue("created_ts"));
    }

    @Test
    @DisplayName("of(entity): @Transient 字段不进入 ParameterSource（任一 key 均不命中）")
    void transientExcluded() {
        User user = new User(1L, "alice", LocalDateTime.now(), "should be ignored");
        SqlParameterSource ps = AnnotatedParameterSource.of(user);

        assertThat(ps.hasValue("displayName")).isFalse();
    }

    @Test
    @DisplayName("of(entity): null 字段值正确传递（key 存在但 value 为 null）")
    void nullValuesPassedThrough() {
        User user = new User(null, null, null, null);
        SqlParameterSource ps = AnnotatedParameterSource.of(user);

        assertThat(ps.hasValue("id")).isTrue();
        assertThat(ps.getValue("id")).isNull();
        assertThat(ps.getValue("userName")).isNull();
    }

    enum Status {ACTIVE}

    static class EnumUser {
        Status status;

        EnumUser(Status status) {
            this.status = status;
        }
    }

    @Test
    @DisplayName("of(entity): enum 字段经 handler.write 转换为 name() 字符串（非裸枚举）")
    void enumWrittenByHandler() {
        EnumUser u = new EnumUser(Status.ACTIVE);
        SqlParameterSource ps = AnnotatedParameterSource.of(u);

        assertThat(ps.getValue("status")).isEqualTo("ACTIVE");
    }

    static class OffsetUser {
        OffsetDateTime ts;

        OffsetUser(OffsetDateTime ts) {
            this.ts = ts;
        }
    }

    @Test
    @DisplayName("of(entity): 无 handler 覆盖的类型宽松回退裸值，不抛异常")
    void unhandledTypeFallsBackToRawValue() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetUser u = new OffsetUser(now);

        FieldHandlerRegistry emptyRegistry = new FieldHandlerRegistry();
        SqlParameterSource ps = AnnotatedParameterSource.of(u, emptyRegistry);

        assertThat(ps.getValue("ts")).isEqualTo(now);
    }

    static class MarkerHandler implements FieldHandler<String> {
        @Override
        public Class<String> javaType() {
            return String.class;
        }

        @Override
        public Set<String> sqlTypeAliases() {
            return Set.of("inet");
        }

        @Override
        public String read(ResultSet rs, int columnIndex) throws SQLException {
            return rs.getString(columnIndex);
        }

        @Override
        public Object write(String value) {
            return "handled:" + value;
        }
    }

    static class InetEntity {
        @Column(value = "ip", sqlType = "inet")
        String ip;

        InetEntity(String ip) {
            this.ip = ip;
        }
    }

    @Test
    @DisplayName("of(entity): @Column(sqlType=...) 路由到 alias handler（写路径经 write 转换）")
    void sqlTypeRoutesToAliasHandler() {
        FieldHandlerRegistry registry = new FieldHandlerRegistry();
        registry.register(new MarkerHandler());
        InetEntity u = new InetEntity("10.0.0.1");

        SqlParameterSource ps = AnnotatedParameterSource.of(u, registry);

        assertThat(ps.getValue("ip")).isEqualTo("handled:10.0.0.1");
    }

    @Test
    @DisplayName("of(entity, strategy): 自定义命名策略下双 key 仍成立（IDENTITY 策略列名=字段名）")
    void customStrategyStillDualKeys() {
        User user = new User(3L, "charlie", LocalDateTime.now(), null);
        SqlParameterSource ps = AnnotatedParameterSource.of(user, ColumnNamingStrategy.IDENTITY);

        assertThat(ps.getValue("userName")).isEqualTo("charlie");
        // IDENTITY 策略下列名 == 字段名，两个 key 实际是同一个字符串
        assertThat(ps.hasValue("userName")).isTrue();
    }

    @Test
    @DisplayName("of(entity, registry, strategy): 四参重载与其余重载等价（默认registry+策略）")
    void fourArgOverloadEquivalence() {
        User user = new User(5L, "erin", LocalDateTime.parse("2026-05-22T09:00:00"), null);
        SqlParameterSource full = AnnotatedParameterSource.of(
                user, FieldHandlerRegistry.class.cast(defaultsRegistry()), ColumnNamingStrategy.CAMEL_TO_UNDERSCORE);
        SqlParameterSource simple = AnnotatedParameterSource.of(user);

        assertThat(full.getValue("userName")).isEqualTo(simple.getValue("userName"));
        assertThat(full.getValue("user_name")).isEqualTo(simple.getValue("user_name"));
    }

    private static FieldHandlerRegistry defaultsRegistry() {
        FieldHandlerRegistry registry = new FieldHandlerRegistry();
        FieldHandlerRegistry.registerBuiltins(registry);
        return registry;
    }
}
