package cn.code91.toolbox.database.registry.handler;

import cn.code91.toolbox.database.spi.FieldHandler;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * <b>枚举兜底 handler</b>
 * <p>按枚举常量名（{@link Enum#name()}）读写。参数化——由
 * {@link cn.code91.toolbox.database.registry.FieldHandlerRegistry#findHandler} 在未找到
 * 该枚举类型专属 handler 时，per-field 动态构造（不预注册进 byJavaType，因为具体枚举类型
 * 无法在 registerBuiltins 阶段穷举）。</p>
 *
 * @param <E> 枚举类型
 */
public final class EnumByNameHandler<E extends Enum<E>> implements FieldHandler<E> {

    private final Class<E> enumType;

    public EnumByNameHandler(Class<E> enumType) {
        this.enumType = enumType;
    }

    @Override
    public Class<E> javaType() {
        return enumType;
    }

    @Override
    public E read(ResultSet rs, int columnIndex) throws SQLException {
        String name = rs.getString(columnIndex);
        if (name == null || rs.wasNull()) {
            return null;
        }
        try {
            return Enum.valueOf(enumType, name);
        } catch (IllegalArgumentException e) {
            throw new SQLException(
                    "未知枚举常量 '" + name + "'（类型 " + enumType.getSimpleName() + "）", e);
        }
    }

    @Override
    public Object write(E value) {
        return value == null ? null : value.name();
    }
}
