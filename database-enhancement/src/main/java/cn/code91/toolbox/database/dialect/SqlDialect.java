package cn.code91.toolbox.database.dialect;

import cn.code91.toolbox.database.registry.FieldHandlerRegistry;

/**
 * <b>SQL 方言 Seam</b>
 * <p>Task 8 的 {@code SqlBuilder}/{@code EntityRowMapper} 只面向本接口，不直接引用任何具体方言
 * 实现类型——这是"P1 仅 {@link PostgresDialect}，未来 P4 加 MysqlDialect 时 L1/L2/L3 与注册表
 * 架构不受影响"的关键：方言差异被完全收敛在 {@link #limitOffset} 与 {@link #registerBuiltins}
 * 两个方法内。</p>
 *
 * <p><b>包放置的已披露裁定</b>：brief 原文把本接口归在 {@code spi} 包，但其
 * {@link #registerBuiltins} 方法签名直接引用 {@link FieldHandlerRegistry}（定义在
 * {@code registry} 包，而 {@code registry} 依赖 {@code spi.FieldHandler}）——若把本接口放进
 * {@code spi} 会形成 {@code spi} ↔ {@code registry} 双向依赖环，违反约束 8 的 ArchUnit
 * "包无环"规则（ArchitectureTest 实测已验证会触发环检测失败）。移到 {@code dialect} 包
 * （与其唯一 P1 实现 {@link PostgresDialect} 同包）后依赖方向变为单向
 * {@code dialect → registry → spi}，无环。这与 {@code naming.ColumnNamingStrategy}
 * Seam 接口和其默认实现同包的既有先例一致，不是另起炉灶。</p>
 */
public interface SqlDialect {

    /** 方言标识（如 "postgresql"），供配置展示与日志使用。 */
    String name();

    /**
     * 把不带分页的 SQL 文本改写为带分页的版本（各方言分页语法不同，如 PG 用 {@code LIMIT/OFFSET}）。
     *
     * @param sql    原 SQL（不含分页子句）
     * @param limit  最大返回行数
     * @param offset 跳过行数
     * @return 带分页子句的 SQL
     */
    String limitOffset(String sql, long limit, long offset);

    /**
     * 向 registry 注册本方言的特化 handler 集（如 PG 的 jsonb/uuid/inet/array/timestamptz）。
     * 由装配层在收集应用自定义 handler/customizer 之前调用，确保方言内置 handler 先于
     * 应用扩展占位（应用扩展仍可通过 alias 不反盖规则安全叠加，见 {@link FieldHandlerRegistry}）。
     */
    void registerBuiltins(FieldHandlerRegistry registry);
}
