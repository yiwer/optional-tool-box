package cn.code91.toolbox.database.registry.handler;

import cn.code91.toolbox.database.spi.FieldHandler;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;

/** {@link LocalDate} 标准 handler。 */
public final class LocalDateHandler implements FieldHandler<LocalDate> {
    @Override
    public Class<LocalDate> javaType() {
        return LocalDate.class;
    }

    @Override
    public LocalDate read(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getObject(columnIndex, LocalDate.class);
    }

    @Override
    public Object write(LocalDate value) {
        return value;
    }
}
