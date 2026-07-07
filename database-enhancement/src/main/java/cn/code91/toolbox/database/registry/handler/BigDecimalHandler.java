package cn.code91.toolbox.database.registry.handler;

import cn.code91.toolbox.database.spi.FieldHandler;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;

/** {@link BigDecimal} 标准 handler。 */
public final class BigDecimalHandler implements FieldHandler<BigDecimal> {
    @Override
    public Class<BigDecimal> javaType() {
        return BigDecimal.class;
    }

    @Override
    public BigDecimal read(ResultSet rs, int columnIndex) throws SQLException {
        return rs.getBigDecimal(columnIndex);
    }

    @Override
    public Object write(BigDecimal value) {
        return value;
    }
}
