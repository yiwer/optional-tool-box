package cn.code91.toolbox.database.registry.handler;

import cn.code91.toolbox.database.spi.FieldHandler;

import java.sql.ResultSet;
import java.sql.SQLException;

/** {@link Boolean} 标准 handler。经 {@code getObject} 读取以兼容部分驱动用数值表示布尔值的场景。 */
public final class BooleanHandler implements FieldHandler<Boolean> {
    @Override
    public Class<Boolean> javaType() {
        return Boolean.class;
    }

    @Override
    public Boolean read(ResultSet rs, int columnIndex) throws SQLException {
        Object value = rs.getObject(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Number n) {
            return n.intValue() != 0;
        }
        return Boolean.parseBoolean(value.toString());
    }

    @Override
    public Object write(Boolean value) {
        return value;
    }
}
