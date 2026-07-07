package cn.code91.toolbox.database.registry.handler;

import cn.code91.toolbox.database.spi.FieldHandler;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

/** {@link LocalDateTime} 标准 handler。 */
public final class LocalDateTimeHandler implements FieldHandler<LocalDateTime> {
    @Override
    public Class<LocalDateTime> javaType() {
        return LocalDateTime.class;
    }

    @Override
    public LocalDateTime read(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getObject(columnIndex, LocalDateTime.class);
    }

    @Override
    public Object write(LocalDateTime value) {
        return value;
    }
}
