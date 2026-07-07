package cn.code91.toolbox.database.registry.handler;

import cn.code91.toolbox.database.spi.FieldHandler;

import java.sql.ResultSet;
import java.sql.SQLException;

/** {@code byte[]} 标准 handler（对应 SQL {@code bytea}/{@code binary}）。 */
public final class ByteArrayHandler implements FieldHandler<byte[]> {
    @Override
    public Class<byte[]> javaType() {
        return byte[].class;
    }

    @Override
    public byte[] read(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getBytes(columnIndex);
    }

    @Override
    public Object write(byte[] value) {
        return value;
    }
}
