package cn.code91.toolbox.database.dialect.handler;

import cn.code91.toolbox.database.spi.FieldHandler;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * <b>PG timestamptz handler</b>
 * <p>读写逻辑与标准 {@code OffsetDateTimeHandler} 相同（PG JDBC 驱动对
 * {@code getObject(idx, OffsetDateTime.class)} 一等支持），独立声明的理由同 {@link PgUuidHandler}：
 * 让 {@code @Column(sqlType = "timestamptz")} 成为可发现的显式声明。</p>
 */
public final class PgTimestamptzHandler implements FieldHandler<OffsetDateTime> {

    @Override
    public Class<OffsetDateTime> javaType() {
        return OffsetDateTime.class;
    }

    @Override
    public Set<String> sqlTypeAliases() {
        return Set.of("timestamptz");
    }

    @Override
    public OffsetDateTime read(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getObject(columnIndex, OffsetDateTime.class);
    }

    @Override
    public Object write(OffsetDateTime value) {
        return value;
    }
}
