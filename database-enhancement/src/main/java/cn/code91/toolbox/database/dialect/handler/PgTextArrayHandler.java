package cn.code91.toolbox.database.dialect.handler;

import cn.code91.toolbox.database.spi.FieldHandler;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

/**
 * <b>PG text[] handler</b>
 * <p>javaType 为 {@code String[]}。写路径见 {@link PgArraySqlValueSupport}。</p>
 */
public final class PgTextArrayHandler implements FieldHandler<String[]> {

    @Override
    public Class<String[]> javaType() {
        return String[].class;
    }

    @Override
    public Set<String> sqlTypeAliases() {
        return Set.of("text[]", "varchar[]");
    }

    @Override
    public String[] read(ResultSet rs, int columnIndex) throws SQLException {
        Array array = rs.getArray(columnIndex);
        if (array == null || rs.wasNull()) {
            return null;
        }
        Object raw = array.getArray();
        if (raw instanceof String[] strings) {
            return strings;
        }
        Object[] objects = (Object[]) raw;
        String[] result = new String[objects.length];
        for (int i = 0; i < objects.length; i++) {
            result[i] = objects[i] == null ? null : objects[i].toString();
        }
        return result;
    }

    @Override
    public Object write(String[] value) {
        if (value == null) {
            return null;
        }
        return new PgArraySqlValueSupport("text", value);
    }
}
