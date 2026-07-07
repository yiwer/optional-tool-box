package cn.code91.toolbox.database.registry.handler;

import cn.code91.toolbox.database.spi.FieldHandler;

import java.sql.ResultSet;
import java.sql.SQLException;

/** {@link String} 标准 handler。 */
public final class StringHandler implements FieldHandler<String> {
    @Override
    public Class<String> javaType() {
        return String.class;
    }

    @Override
    public String read(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getString(columnIndex);
    }

    @Override
    public Object write(String value) {
        return value;
    }
}
