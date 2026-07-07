package cn.code91.toolbox.database.dialect.handler;

import cn.code91.toolbox.database.spi.FieldHandler;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

/**
 * <b>PG uuid handler</b>
 * <p>读写逻辑与标准 {@code UuidHandler} 相同（PG JDBC 驱动对 {@code UUID} 有一等支持，
 * {@code getObject(idx, UUID.class)} 直接可用，无需 {@code PGobject} 包装）——独立声明只是
 * 为了让 {@code @Column(sqlType = "uuid")} 成为一个可路由、可发现的显式声明（与 jsonb/inet
 * 同构的 API 心智模型），而不强制要求消费方总是依赖"未声明 sqlType 时按 Java 类型隐式匹配"
 * 这一条隐性规则。javaType 复用 {@link UUID}，按 alias 不反盖规则，若标准 handler 先注册
 * 占住 slot，本 handler 仍可通过 sqlType 精确路由到达。</p>
 */
public final class PgUuidHandler implements FieldHandler<UUID> {

    @Override
    public Class<UUID> javaType() {
        return UUID.class;
    }

    @Override
    public Set<String> sqlTypeAliases() {
        return Set.of("uuid");
    }

    @Override
    public UUID read(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getObject(columnIndex, UUID.class);
    }

    @Override
    public Object write(UUID value) {
        return value;
    }
}
