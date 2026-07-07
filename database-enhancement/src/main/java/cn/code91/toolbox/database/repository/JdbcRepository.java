package cn.code91.toolbox.database.repository;

import java.util.List;
import java.util.Optional;

/**
 * <b>L3 通用 CRUD 仓库 Seam</b>
 * <p>在 L2 三件套（{@code SqlBuilder} + {@code AnnotatedParameterSource} + {@code EntityRowMapper}）
 * 之上包装 by-id CRUD API。默认实现：{@code PgJdbcRepository}。</p>
 *
 * <p>最小 7 op（YAGNI；save/findAll/batch 等留待后续演进）。</p>
 *
 * <p><b>约束 3 的落点</b>：全部方法保留 Spring 异常语义（{@code DataAccessException} 家族），
 * <b>不返回 {@code Result}</b>——数据访问路径的异常需自然传播以触发 {@code @Transactional} 回滚
 * （见总体设计 §2.3、02 设计文档 §4.5）。这是全仓库唯一的 Result 化豁免，任何审查都不应把
 * "本接口不返 Result"当作缺陷。</p>
 *
 * @param <T>  entity 类型
 * @param <ID> 主键类型（{@code @Id} 字段类型）
 */
public interface JdbcRepository<T, ID> {

    /** INSERT 新 entity（含全部非 {@code @Transient} 字段，含 {@code @Id}）。Spring DAO 异常透传。 */
    void insert(T entity);

    /** UPDATE 按 {@code @Id} 字段定位；返回 affected rows（0 表示 entity 不存在）。Spring DAO 异常透传。 */
    int updateById(T entity);

    /**
     * SELECT 按 ID；命中返回 {@code Optional.of(entity)}，未命中返回 {@code Optional.empty()}
     * （内部收敛 {@code EmptyResultDataAccessException}，其余异常仍透传）。
     */
    Optional<T> findById(ID id);

    /** DELETE 按 ID；返回 affected rows。Spring DAO 异常透传。 */
    int deleteById(ID id);

    /**
     * {@code SELECT 1 FROM <table> WHERE <id> = :<id> LIMIT 1}；命中 true，未命中 false
     * （内部收敛 {@code EmptyResultDataAccessException}）。
     */
    boolean existsById(ID id);

    /** {@code SELECT COUNT(*) FROM <table>}。 */
    long count();

    /**
     * 分页查询：按方言分页语法（{@code SqlDialect.limitOffset}）改写 SELECT，{@code ORDER BY <id>}。
     * {@code limit<=0} 或 {@code offset<0} 抛 {@link IllegalArgumentException}；无硬上限
     * （上限由调用方约束）。
     */
    List<T> list(long limit, long offset);
}
