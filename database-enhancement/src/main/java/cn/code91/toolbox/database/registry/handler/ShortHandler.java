package cn.code91.toolbox.database.registry.handler;

import cn.code91.toolbox.database.spi.FieldHandler;

import java.sql.ResultSet;
import java.sql.SQLException;

/** {@link Short} 标准 handler。经 {@code getObject} 读取以兼容不同驱动返回的数值子类型。 */
public final class ShortHandler implements FieldHandler<Short> {
    @Override
    public Class<Short> javaType() {
        return Short.class;
    }

    @Override
    public Short read(ResultSet rs, int columnIndex) throws SQLException {
        Object value = rs.getObject(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof Short s) {
            return s;
        }
        if (value instanceof Number n) {
            return n.shortValue();
        }
        return Short.parseShort(value.toString());
    }

    @Override
    public Object write(Short value) {
        return value;
    }
}
