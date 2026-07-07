package cn.code91.toolbox.database.registry.handler;

import cn.code91.toolbox.database.spi.FieldHandler;

import java.sql.ResultSet;
import java.sql.SQLException;

/** {@link Integer} 标准 handler。经 {@code getObject} 读取以兼容不同驱动返回的数值子类型。 */
public final class IntegerHandler implements FieldHandler<Integer> {
    @Override
    public Class<Integer> javaType() {
        return Integer.class;
    }

    @Override
    public Integer read(ResultSet rs, int columnIndex) throws SQLException {
        Object value = rs.getObject(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof Integer i) {
            return i;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(value.toString());
    }

    @Override
    public Object write(Integer value) {
        return value;
    }
}
