package cn.code91.toolbox.database.dialect.handler;

import org.springframework.jdbc.support.SqlValue;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * <b>PG 数组写路径的共享 {@link SqlValue} 实现</b>
 * <p>{@code write()} 方法本身拿不到 {@link java.sql.Connection}（{@code createArrayOf}
 * 需要它），故用 {@code SqlValue} 把"构造驱动 {@link Array}"延迟到 Spring 绑定参数时刻
 * （{@code setValue}）。{@code cleanup()} 释放 {@code Array} 持有的资源（PG JDBC 文档要求）。
 * {@code PgTextArrayHandler}/{@code PgIntArrayHandler} 共用本实现，避免重复。</p>
 */
final class PgArraySqlValueSupport implements SqlValue {

    private final String pgArrayType;
    private final Object[] elements;
    private Array array;

    PgArraySqlValueSupport(String pgArrayType, Object[] elements) {
        this.pgArrayType = pgArrayType;
        this.elements = elements;
    }

    @Override
    public void setValue(PreparedStatement ps, int paramIndex) throws SQLException {
        this.array = ps.getConnection().createArrayOf(pgArrayType, elements);
        ps.setArray(paramIndex, this.array);
    }

    @Override
    public void cleanup() {
        if (array != null) {
            try {
                array.free();
            } catch (SQLException ignored) {
                // free() 失败不影响已完成的写操作，仅为资源清理的最大努力尝试
            }
        }
    }
}
