package cn.code91.toolbox.database.dialect;

import cn.code91.toolbox.database.dialect.handler.PgInetHandler;
import cn.code91.toolbox.database.dialect.handler.PgIntArrayHandler;
import cn.code91.toolbox.database.dialect.handler.PgJsonbHandler;
import cn.code91.toolbox.database.dialect.handler.PgTextArrayHandler;
import cn.code91.toolbox.database.dialect.handler.PgTimestamptzHandler;
import cn.code91.toolbox.database.dialect.handler.PgUuidHandler;
import cn.code91.toolbox.database.registry.FieldHandlerRegistry;

/**
 * <b>PostgreSQL 方言（P1 唯一实现）</b>
 * <p>本类及 {@link SqlDialect} 门面本身<b>不直接引用</b> {@code org.postgresql.**} 或
 * {@code com.p6spy.**} 类型（ArchUnit 规则守卫，见 {@code ArchitectureTest}）——PG
 * 驱动/PGobject 相关类型全部封装在 {@link cn.code91.toolbox.database.dialect.handler} 子包内，
 * 本类只是"组装"这些 handler，保证缺 postgresql 可选依赖时（消费方未引入驱动 jar），除
 * {@link #registerBuiltins} 的具体 handler 类无法加载之外，其余门面代码路径仍可正常加载
 * （可选依赖的价值所在）。</p>
 */
public final class PostgresDialect implements SqlDialect {

    @Override
    public String name() {
        return "postgresql";
    }

    @Override
    public String limitOffset(String sql, long limit, long offset) {
        return sql + " LIMIT " + limit + " OFFSET " + offset;
    }

    @Override
    public void registerBuiltins(FieldHandlerRegistry registry) {
        registry.register(new PgInetHandler());
        registry.register(new PgUuidHandler());
        registry.register(new PgTimestamptzHandler());
        registry.register(new PgTextArrayHandler());
        registry.register(new PgIntArrayHandler());
        registry.registerFactory("jsonb", PgJsonbHandler::new);
        registry.registerFactory("json", PgJsonbHandler::new);
    }
}
