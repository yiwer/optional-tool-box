package cn.code91.toolbox.database.registry.handler;

import cn.code91.toolbox.database.spi.FieldHandler;

import java.sql.ResultSet;
import java.sql.SQLException;

/** {@link Long} 标准 handler。经 {@code getObject} 读取以兼容不同驱动返回的数值子类型。 */
public final class LongHandler implements FieldHandler<Long> {
    @Override
    public Class<Long> javaType() {
        return Long.class;
    }

    @Override
    public Long read(ResultSet rs, int columnIndex) throws SQLException {
        Object value = rs.getObject(columnIndex);
        if (value == null) {
            return null;
        }
        if (value instanceof Long l) {
            return l;
        }
        if (value instanceof Number n) {
            return n.longValue();
        }
        return Long.parseLong(value.toString());
    }

    @Override
    public Object write(Long value) {
        return value;
    }
}
