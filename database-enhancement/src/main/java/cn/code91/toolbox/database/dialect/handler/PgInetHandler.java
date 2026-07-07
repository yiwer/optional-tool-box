package cn.code91.toolbox.database.dialect.handler;

import cn.code91.toolbox.database.spi.FieldHandler;
import org.postgresql.util.PGobject;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

/**
 * <b>PG inet/cidr handler</b>
 * <p>javaType 声明为 {@link String}，但按 alias 不反盖规则不覆盖 {@code byJavaType[String]}
 * 中的通用 {@code StringHandler}——仅经 {@code sqlType="inet"}（或 "cidr"）精确路由可达。
 * 这正是"同一 Java 类型（String）多种 SQL 表示（varchar 与 inet）"的典型场景，也是
 * alias 路由机制存在的原因（见 {@code FieldHandlerRegistry} 类文档）。</p>
 */
public final class PgInetHandler implements FieldHandler<String> {

    @Override
    public Class<String> javaType() {
        return String.class;
    }

    @Override
    public Set<String> sqlTypeAliases() {
        return Set.of("inet", "cidr");
    }

    @Override
    public String read(ResultSet rs, int columnIndex) throws SQLException {
        PGobject value = rs.getObject(columnIndex, PGobject.class);
        return value == null ? null : value.getValue();
    }

    @Override
    public Object write(String value) throws SQLException {
        if (value == null) {
            return null;
        }
        PGobject pgObject = new PGobject();
        pgObject.setType("inet");
        pgObject.setValue(value);
        return pgObject;
    }
}
