package cn.code91.toolbox.database.dialect.handler;

import cn.code91.toolbox.database.spi.FieldHandler;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

/**
 * <b>PG integer[] handler</b>
 * <p>javaType 为 {@code int[]}（primitive 数组，需反射装箱/拆箱，见 {@link #read}/{@link #write}）。
 * 写路径见 {@link PgArraySqlValueSupport}。</p>
 */
public final class PgIntArrayHandler implements FieldHandler<int[]> {

    @Override
    public Class<int[]> javaType() {
        return int[].class;
    }

    @Override
    public Set<String> sqlTypeAliases() {
        return Set.of("int[]", "integer[]", "int4[]");
    }

    @Override
    public int[] read(ResultSet rs, int columnIndex) throws SQLException {
        Array array = rs.getArray(columnIndex);
        if (array == null || rs.wasNull()) {
            return null;
        }
        Object raw = array.getArray();
        if (raw instanceof int[] ints) {
            return ints;
        }
        Object[] objects = (Object[]) raw;
        int[] result = new int[objects.length];
        for (int i = 0; i < objects.length; i++) {
            result[i] = ((Number) objects[i]).intValue();
        }
        return result;
    }

    @Override
    public Object write(int[] value) {
        if (value == null) {
            return null;
        }
        Integer[] boxed = new Integer[value.length];
        for (int i = 0; i < value.length; i++) {
            boxed[i] = value[i];
        }
        return new PgArraySqlValueSupport("integer", boxed);
    }
}
