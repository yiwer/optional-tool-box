package cn.code91.toolbox.database.registry.handler;

import cn.code91.toolbox.database.spi.FieldHandler;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** {@link UUID} 标准 handler。 */
public final class UuidHandler implements FieldHandler<UUID> {
    @Override
    public Class<UUID> javaType() {
        return UUID.class;
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
