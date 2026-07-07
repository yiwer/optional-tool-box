package cn.code91.toolbox.database.registry.handler;

import cn.code91.toolbox.database.spi.FieldHandler;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;

/**
 * {@link OffsetDateTime} 标准 handler（对应 PG {@code timestamptz}，见 brief 要求的
 * "日期时间 java.time 全套"）。
 */
public final class OffsetDateTimeHandler implements FieldHandler<OffsetDateTime> {
    @Override
    public Class<OffsetDateTime> javaType() {
        return OffsetDateTime.class;
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
