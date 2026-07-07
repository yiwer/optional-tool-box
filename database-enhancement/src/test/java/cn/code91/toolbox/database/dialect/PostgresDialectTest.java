package cn.code91.toolbox.database.dialect;

import cn.code91.toolbox.database.registry.FieldHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PostgresDialect} 的方言标识、分页 SQL 改写、内置 PG 特化 handler 注册。
 * PG handler 真实读写（PGobject/Array 等驱动类型参与）由 Testcontainers PostgreSQL IT 覆盖，
 * 本测试只验证纯逻辑部分（无需真实连接）。
 */
@DisplayName("PostgresDialect")
class PostgresDialectTest {

    private final PostgresDialect dialect = new PostgresDialect();

    @Test
    @DisplayName("name() 返回 postgresql")
    void nameIsPostgresql() {
        assertThat(dialect.name()).isEqualTo("postgresql");
    }

    @Test
    @DisplayName("limitOffset 追加 PG 分页子句")
    void limitOffsetAppendsPgClause() {
        String sql = dialect.limitOffset("SELECT * FROM t_order", 20, 40);

        assertThat(sql).isEqualTo("SELECT * FROM t_order LIMIT 20 OFFSET 40");
    }

    @Test
    @DisplayName("registerBuiltins 注册 PG 特化 handler：uuid/inet 经 sqlType 精确路由")
    void registerBuiltinsWiresSqlTypeAliasHandlers() {
        FieldHandlerRegistry registry = new FieldHandlerRegistry();

        dialect.registerBuiltins(registry);

        assertThat(registry.findHandlerForSqlType("inet", String.class)).isPresent();
        assertThat(registry.findHandlerForSqlType("jsonb", Object.class)).isPresent();
        assertThat(registry.findHandlerForSqlType("json", Object.class)).isPresent();
    }

    @Test
    @DisplayName("registerBuiltins 后 uuid byJavaType 仍可直接解析（无 alias 冲突）")
    void registerBuiltinsDoesNotBreakDirectUuidLookup() {
        FieldHandlerRegistry registry = new FieldHandlerRegistry();
        FieldHandlerRegistry.registerBuiltins(registry);

        dialect.registerBuiltins(registry);

        assertThat(registry.findHandler(UUID.class)).isPresent();
    }

    @Test
    @DisplayName("registerBuiltins 后 String byJavaType 未被 inet handler 反盖（alias 不反盖规则）")
    void registerBuiltinsDoesNotOverwriteStringWithInetHandler() {
        FieldHandlerRegistry registry = new FieldHandlerRegistry();
        FieldHandlerRegistry.registerBuiltins(registry);

        dialect.registerBuiltins(registry);

        // byJavaType[String] 仍是通用 StringHandler，不是带 "inet" alias 的特化 handler
        assertThat(registry.findHandler(String.class)).get()
                .isNotInstanceOf(registry.findHandlerForSqlType("inet", String.class).orElseThrow().getClass());
    }

    @Test
    @DisplayName("registerBuiltins 注册数组 handler：text[]/int[] 经 sqlType 精确路由")
    void registerBuiltinsWiresArrayHandlers() {
        FieldHandlerRegistry registry = new FieldHandlerRegistry();

        dialect.registerBuiltins(registry);

        assertThat(registry.findHandlerForSqlType("text[]", String[].class)).isPresent();
        assertThat(registry.findHandlerForSqlType("int[]", int[].class)).isPresent();
    }
}
