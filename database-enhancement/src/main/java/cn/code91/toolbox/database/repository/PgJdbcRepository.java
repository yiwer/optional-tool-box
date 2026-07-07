package cn.code91.toolbox.database.repository;

import cn.code91.toolbox.database.dialect.SqlDialect;
import cn.code91.toolbox.database.mapper.EntityRowMapper;
import cn.code91.toolbox.database.mapper.SqlBuilder;
import cn.code91.toolbox.database.params.AnnotatedParameterSource;
import cn.code91.toolbox.database.params.EntityIntrospector;
import cn.code91.toolbox.database.params.FieldMapping;
import cn.code91.toolbox.database.registry.FieldHandlerRegistry;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.EmptySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * <b>L3 {@link JdbcRepository} 默认实现（PostgreSQL / {@link SqlDialect} 通用方言 seam）</b>
 * <p>构造器注入 entityClass + idClass + jdbc + registry + dialect；构造期 prefetch 7 个 SQL 串
 * + {@link EntityRowMapper} + idColumnName，调用热路径零 lookup。构造期即触发
 * {@link SqlBuilder} 早失败（0/≥2 {@code @Id} 等 entity 校验错误比运行期发现更早，配置期
 * fail-fast，约束 3 允许的形态）。</p>
 *
 * <p>不做 autoconfigure bean（L3 非单例——不同实体类型需要不同的 {@code PgJdbcRepository}
 * 实例），由消费方按实体构造，与 beacon-database 一致。</p>
 *
 * <p>{@code existsById}/{@code count}/{@code list} 的 SQL 由本类内部拼接（不复用
 * {@link SqlBuilder} 的四件套，因为四件套只覆盖 by-id CRUD，这三个 op 语义不同）。
 * {@code list} 的分页子句通过 {@link SqlDialect#limitOffset} 生成（各方言分页语法不同，
 * 本类不硬编码 {@code LIMIT/OFFSET} 字面语法）。{@code findById}/{@code existsById} 内部收敛
 * {@code EmptyResultDataAccessException} 为 {@code Optional.empty()}/{@code false}；
 * 其余 op 的 Spring DAO 异常原样透传（约束 3：不返 {@code Result}）。</p>
 *
 * @param <T>  entity 类型
 * @param <ID> 主键类型
 */
public final class PgJdbcRepository<T, ID> implements JdbcRepository<T, ID> {

    private final NamedParameterJdbcTemplate jdbc;
    private final String insertSql;
    private final String updateByIdSql;
    private final String selectByIdSql;
    private final String deleteByIdSql;
    private final String existsByIdSql;
    private final String countSql;
    private final String listSqlTemplate;
    private final String idColumnName;
    private final EntityRowMapper<T> rowMapper;
    private final FieldHandlerRegistry registry;
    private final SqlDialect dialect;

    public PgJdbcRepository(
            Class<T> entityClass,
            Class<ID> idClass,
            NamedParameterJdbcTemplate jdbc,
            FieldHandlerRegistry registry,
            SqlDialect dialect) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.dialect = dialect;

        // 调用顺序固定 insert → updateById → selectById → deleteById；
        // 0 @Id entity 第一个抛 ISE 的是 updateById（message 含 UPDATE_BY_ID）。
        this.insertSql = SqlBuilder.insert(entityClass);
        this.updateByIdSql = SqlBuilder.updateById(entityClass);
        this.selectByIdSql = SqlBuilder.selectById(entityClass);
        this.deleteByIdSql = SqlBuilder.deleteById(entityClass);

        this.idColumnName = EntityIntrospector.of(entityClass).fields().stream()
                .filter(FieldMapping::isId).findFirst()
                .map(FieldMapping::sqlColumnName)
                .orElseThrow(() -> new IllegalStateException(
                        "entity " + entityClass.getName()
                                + " has no @Id field, required by JdbcRepository"));

        String tableName = EntityIntrospector.resolveTableName(entityClass);
        this.existsByIdSql = "SELECT 1 FROM " + tableName
                + " WHERE " + this.idColumnName + " = :" + this.idColumnName + " LIMIT 1";
        this.countSql = "SELECT COUNT(*) FROM " + tableName;

        String selectColumns = EntityIntrospector.of(entityClass).fields().stream()
                .map(FieldMapping::sqlColumnName)
                .collect(Collectors.joining(", "));
        this.listSqlTemplate = "SELECT " + selectColumns + " FROM " + tableName
                + " ORDER BY " + this.idColumnName;

        this.rowMapper = new EntityRowMapper<>(entityClass, registry);
    }

    @Override
    public void insert(T entity) {
        jdbc.update(insertSql, AnnotatedParameterSource.of(entity, registry));
    }

    @Override
    public int updateById(T entity) {
        return jdbc.update(updateByIdSql, AnnotatedParameterSource.of(entity, registry));
    }

    @Override
    public Optional<T> findById(ID id) {
        try {
            T result = jdbc.queryForObject(selectByIdSql, idParam(id), rowMapper);
            return Optional.ofNullable(result);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public int deleteById(ID id) {
        return jdbc.update(deleteByIdSql, idParam(id));
    }

    @Override
    public boolean existsById(ID id) {
        try {
            jdbc.queryForObject(existsByIdSql, idParam(id), Integer.class);
            return true;
        } catch (EmptyResultDataAccessException e) {
            return false;
        }
    }

    @Override
    public long count() {
        Long result = jdbc.queryForObject(countSql, EmptySqlParameterSource.INSTANCE, Long.class);
        return result != null ? result : 0L;
    }

    @Override
    public List<T> list(long limit, long offset) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be > 0, got " + limit);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be >= 0, got " + offset);
        }
        String listSql = dialect.limitOffset(listSqlTemplate, limit, offset);
        return jdbc.query(listSql, EmptySqlParameterSource.INSTANCE, rowMapper);
    }

    private SqlParameterSource idParam(ID id) {
        return new MapSqlParameterSource(idColumnName, id);
    }
}
