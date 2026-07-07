package cn.code91.toolbox.database.spi;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;

/**
 * <b>单 Java 字段类型 ↔ SQL 列的统一 read/write 扩展点</b>
 * <p>实现必须 stateless + thread-safe（{@link cn.code91.toolbox.database.registry.FieldHandlerRegistry}
 * 以单例形式复用）。参数化 handler（如枚举兜底 handler）由 registry 的
 * {@code findHandler} 按需 per-field 构造。</p>
 *
 * <p><b>约束 3 的落点</b>：{@link #read}/{@link #write} 保留受检异常 {@link SQLException}
 * 语义，不包 {@code Result}——数据访问路径的异常需自然传播以触发 {@code @Transactional} 回滚
 * （见总体设计 §2.3、02 设计文档 §4.5）。</p>
 *
 * @param <J> Java 字段类型
 */
public interface FieldHandler<J> {

    /** 本 handler 处理的 Java 类型。 */
    Class<J> javaType();

    /** DB → Java。 */
    J read(ResultSet rs, int columnIndex) throws SQLException;

    /** Java → JDBC 可绑定值（可返回 driver 特化类型，如 PG 的 {@code PGobject}）。 */
    Object write(J value) throws SQLException;

    /**
     * 本 handler 认领的方言类型别名（如 "jsonb"、"integer[]"）。默认空集：仅按 Java 类型匹配，
     * 不参与 {@code bySqlTypeAlias} 索引。
     */
    default Set<String> sqlTypeAliases() {
        return Set.of();
    }
}
