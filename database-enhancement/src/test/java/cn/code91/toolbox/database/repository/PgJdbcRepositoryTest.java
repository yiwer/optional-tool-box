package cn.code91.toolbox.database.repository;

import cn.code91.toolbox.database.annotation.Column;
import cn.code91.toolbox.database.annotation.Id;
import cn.code91.toolbox.database.annotation.Table;
import cn.code91.toolbox.database.dialect.PostgresDialect;
import cn.code91.toolbox.database.dialect.SqlDialect;
import cn.code91.toolbox.database.mapper.EntityRowMapper;
import cn.code91.toolbox.database.registry.FieldHandlerRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.EmptySqlParameterSource;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PgJdbcRepository}：构造期 prefetch + 早失败、全 7 op（mock
 * {@link NamedParameterJdbcTemplate}）、约束 3 事务语义钉住测试。
 */
@DisplayName("PgJdbcRepository - 构造期 prefetch + 早失败 + 全 op")
class PgJdbcRepositoryTest {

    @Table("users")
    static class User {
        @Id
        Long id;
        String userName;
        @Column("email_addr")
        String email;

        User() {
        }
    }

    @Table("legacy_users")
    static class LegacyUser {
        @Id
        @Column("user_pk")
        Long id;
        String label;

        LegacyUser() {
        }
    }

    static class NoIdEntity {
        String onlyField;

        NoIdEntity() {
        }
    }

    private static FieldHandlerRegistry defaultsRegistry() {
        FieldHandlerRegistry registry = new FieldHandlerRegistry();
        FieldHandlerRegistry.registerBuiltins(registry);
        new PostgresDialect().registerBuiltins(registry);
        return registry;
    }

    private static final SqlDialect DIALECT = new PostgresDialect();

    @Nested
    @DisplayName("ctor")
    class Ctor {
        @Test
        @DisplayName("标准 entity 构造成功")
        void ctorSucceedsWithStandardEntity() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            assertThatCode(() ->
                    new PgJdbcRepository<>(User.class, Long.class, jdbc, defaultsRegistry(), DIALECT))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("entity 0 @Id 构造期抛 ISE（updateById 第一个触发校验，message 含 UPDATE_BY_ID）")
        void ctorThrowsForNoIdEntity() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            assertThatThrownBy(() ->
                    new PgJdbcRepository<>(NoIdEntity.class, Long.class, jdbc, defaultsRegistry(), DIALECT))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@Id")
                    .hasMessageContaining("UPDATE_BY_ID");
        }

        @Test
        @DisplayName("自定义 id 列名（@Column）entity 构造成功")
        void ctorRespectsCustomIdColumnName() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            assertThatCode(() ->
                    new PgJdbcRepository<>(LegacyUser.class, Long.class, jdbc, defaultsRegistry(), DIALECT))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("insert")
    class Insert {
        @Test
        @DisplayName("insert 调 jdbc.update(insertSql, SqlParameterSource) 含正确占位符 key")
        void insertCallsJdbcUpdateWithCorrectSql() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            PgJdbcRepository<User, Long> repo =
                    new PgJdbcRepository<>(User.class, Long.class, jdbc, defaultsRegistry(), DIALECT);

            User user = new User();
            user.id = 1L;
            user.userName = "alice";
            user.email = "a@b";

            repo.insert(user);

            ArgumentCaptor<SqlParameterSource> srcCap = ArgumentCaptor.forClass(SqlParameterSource.class);
            verify(jdbc).update(
                    eq("INSERT INTO users (id, user_name, email_addr) VALUES (:id, :user_name, :email_addr)"),
                    srcCap.capture());
            assertThat(srcCap.getValue().hasValue("id")).isTrue();
            assertThat(srcCap.getValue().hasValue("user_name")).isTrue();
        }
    }

    @Nested
    @DisplayName("findById")
    class FindById {
        @Test
        @DisplayName("findById 命中返 Optional.of(entity)")
        void findByIdReturnsOptionalOfEntityWhenFound() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            PgJdbcRepository<User, Long> repo =
                    new PgJdbcRepository<>(User.class, Long.class, jdbc, defaultsRegistry(), DIALECT);

            User stub = new User();
            stub.id = 1L;
            stub.userName = "alice";
            when(jdbc.queryForObject(
                    eq("SELECT id, user_name, email_addr FROM users WHERE id = :id"),
                    any(MapSqlParameterSource.class),
                    any(EntityRowMapper.class))).thenReturn(stub);

            Optional<User> result = repo.findById(1L);

            assertThat(result).isPresent();
            assertThat(result.get()).isSameAs(stub);
        }

        @Test
        @DisplayName("findById 未命中收敛 EmptyResultDataAccessException → Optional.empty")
        void findByIdReturnsEmptyWhenAbsent() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            PgJdbcRepository<User, Long> repo =
                    new PgJdbcRepository<>(User.class, Long.class, jdbc, defaultsRegistry(), DIALECT);

            when(jdbc.queryForObject(
                    any(String.class), any(MapSqlParameterSource.class), any(EntityRowMapper.class)))
                    .thenThrow(new EmptyResultDataAccessException(1));

            Optional<User> result = repo.findById(999L);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("自定义 id 列名（@Column user_pk）跟随到 SQL + ParameterSource key")
        void findByIdWithCustomIdColumnName() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            PgJdbcRepository<LegacyUser, Long> repo =
                    new PgJdbcRepository<>(LegacyUser.class, Long.class, jdbc, defaultsRegistry(), DIALECT);

            ArgumentCaptor<MapSqlParameterSource> srcCap = ArgumentCaptor.forClass(MapSqlParameterSource.class);
            when(jdbc.queryForObject(any(String.class), srcCap.capture(), any(EntityRowMapper.class)))
                    .thenReturn(null);

            repo.findById(42L);

            verify(jdbc).queryForObject(
                    eq("SELECT user_pk, label FROM legacy_users WHERE user_pk = :user_pk"),
                    any(MapSqlParameterSource.class),
                    any(EntityRowMapper.class));
            assertThat(srcCap.getValue().getValue("user_pk")).isEqualTo(42L);
        }
    }

    @Nested
    @DisplayName("updateById")
    class UpdateById {
        @Test
        @DisplayName("updateById 调 jdbc.update(updateByIdSql, source) 返 affected rows")
        void updateByIdCallsJdbcUpdate() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            PgJdbcRepository<User, Long> repo =
                    new PgJdbcRepository<>(User.class, Long.class, jdbc, defaultsRegistry(), DIALECT);

            User user = new User();
            user.id = 1L;
            user.userName = "alice2";

            when(jdbc.update(any(String.class), any(SqlParameterSource.class))).thenReturn(1);

            int affected = repo.updateById(user);

            assertThat(affected).isEqualTo(1);
            verify(jdbc).update(
                    eq("UPDATE users SET user_name = :user_name, email_addr = :email_addr WHERE id = :id"),
                    any(SqlParameterSource.class));
        }
    }

    @Nested
    @DisplayName("deleteById")
    class DeleteById {
        @Test
        @DisplayName("deleteById 调 jdbc.update(deleteByIdSql, idParam) 返 affected rows")
        void deleteByIdCallsJdbcUpdate() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            PgJdbcRepository<User, Long> repo =
                    new PgJdbcRepository<>(User.class, Long.class, jdbc, defaultsRegistry(), DIALECT);

            ArgumentCaptor<MapSqlParameterSource> srcCap = ArgumentCaptor.forClass(MapSqlParameterSource.class);
            when(jdbc.update(any(String.class), srcCap.capture())).thenReturn(1);

            int affected = repo.deleteById(1L);

            assertThat(affected).isEqualTo(1);
            verify(jdbc).update(
                    eq("DELETE FROM users WHERE id = :id"),
                    any(SqlParameterSource.class));
            assertThat(srcCap.getValue().getValue("id")).isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("existsById")
    class ExistsById {
        @Test
        @DisplayName("existsById 命中返 true（mock SELECT 1 返 1）")
        void existsByIdReturnsTrueWhenFound() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            PgJdbcRepository<User, Long> repo =
                    new PgJdbcRepository<>(User.class, Long.class, jdbc, defaultsRegistry(), DIALECT);

            when(jdbc.queryForObject(
                    eq("SELECT 1 FROM users WHERE id = :id LIMIT 1"),
                    any(MapSqlParameterSource.class),
                    eq(Integer.class))).thenReturn(1);

            assertThat(repo.existsById(1L)).isTrue();
        }

        @Test
        @DisplayName("existsById 未命中收敛 EmptyResultDataAccessException → false")
        void existsByIdReturnsFalseWhenAbsent() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            PgJdbcRepository<User, Long> repo =
                    new PgJdbcRepository<>(User.class, Long.class, jdbc, defaultsRegistry(), DIALECT);

            when(jdbc.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Integer.class)))
                    .thenThrow(new EmptyResultDataAccessException(1));

            assertThat(repo.existsById(999L)).isFalse();
        }

        @Test
        @DisplayName("existsById SQL 含 LIMIT 1（防大表全扫）")
        void existsByIdSqlContainsLimit1() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            PgJdbcRepository<User, Long> repo =
                    new PgJdbcRepository<>(User.class, Long.class, jdbc, defaultsRegistry(), DIALECT);

            when(jdbc.queryForObject(any(String.class), any(MapSqlParameterSource.class), eq(Integer.class)))
                    .thenReturn(1);

            repo.existsById(1L);

            ArgumentCaptor<String> sqlCap = ArgumentCaptor.forClass(String.class);
            verify(jdbc).queryForObject(sqlCap.capture(), any(MapSqlParameterSource.class), eq(Integer.class));
            assertThat(sqlCap.getValue()).isEqualTo("SELECT 1 FROM users WHERE id = :id LIMIT 1");
        }
    }

    @Nested
    @DisplayName("count")
    class Count {
        @Test
        @DisplayName("count 调 jdbc.queryForObject(countSql, EmptySource, Long.class)")
        void countCallsJdbcQueryForObject() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            PgJdbcRepository<User, Long> repo =
                    new PgJdbcRepository<>(User.class, Long.class, jdbc, defaultsRegistry(), DIALECT);

            when(jdbc.queryForObject(
                    eq("SELECT COUNT(*) FROM users"),
                    eq(EmptySqlParameterSource.INSTANCE),
                    eq(Long.class))).thenReturn(42L);

            assertThat(repo.count()).isEqualTo(42L);
        }

        @Test
        @DisplayName("count 返 null 时兜底 0L")
        void countReturnsZeroWhenJdbcReturnsNull() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            PgJdbcRepository<User, Long> repo =
                    new PgJdbcRepository<>(User.class, Long.class, jdbc, defaultsRegistry(), DIALECT);

            when(jdbc.queryForObject(any(String.class), any(EmptySqlParameterSource.class), eq(Long.class)))
                    .thenReturn(null);

            assertThat(repo.count()).isEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("list")
    class ListOp {
        @Test
        @DisplayName("list → jdbc.query(listSql via SqlDialect.limitOffset, {}, rowMapper)")
        void list() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            PgJdbcRepository<User, Long> repo =
                    new PgJdbcRepository<>(User.class, Long.class, jdbc, defaultsRegistry(), DIALECT);

            when(jdbc.query(anyString(), any(SqlParameterSource.class),
                    org.mockito.ArgumentMatchers.<RowMapper<User>>any()))
                    .thenReturn(List.of(new User()));

            List<User> result = repo.list(20L, 40L);

            assertThat(result).hasSize(1);
            ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
            verify(jdbc).query(sql.capture(), any(SqlParameterSource.class),
                    org.mockito.ArgumentMatchers.<RowMapper<User>>any());
            // PostgresDialect.limitOffset 拼接 "LIMIT n OFFSET m"（字面值，非占位符——与
            // beacon/PostgresDialect 一致：分页数值直接拼字面量，不走参数绑定）。
            assertThat(sql.getValue())
                    .contains("FROM users")
                    .contains("ORDER BY id")
                    .contains("LIMIT 20 OFFSET 40");
        }

        @Test
        @DisplayName("list limit<=0 抛 IllegalArgumentException")
        void listRejectsNonPositiveLimit() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            PgJdbcRepository<User, Long> repo =
                    new PgJdbcRepository<>(User.class, Long.class, jdbc, defaultsRegistry(), DIALECT);

            assertThatThrownBy(() -> repo.list(0L, 0L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("limit");
        }

        @Test
        @DisplayName("list offset<0 抛 IllegalArgumentException")
        void listRejectsNegativeOffset() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            PgJdbcRepository<User, Long> repo =
                    new PgJdbcRepository<>(User.class, Long.class, jdbc, defaultsRegistry(), DIALECT);

            assertThatThrownBy(() -> repo.list(20L, -1L))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("offset");
        }
    }

    /**
     * <b>约束 3 事务语义钉住测试</b>
     * <p>Repository op 抛出的必须是 Spring {@link DataAccessException} 家族异常（原样透传），
     * <b>不是</b>被包成 {@code Result} 的错误值——{@code @Transactional} 依赖异常传播触发回滚，
     * 若未来有人误改约束 3（把本层包成 Result），本测试会失败并暴露问题。</p>
     */
    @Nested
    @DisplayName("约束 3：异常语义钉住（不返 Result）")
    class ExceptionSemanticsPinned {

        @Test
        @DisplayName("insert 遇 DataAccessException 原样抛出（非返回值，非 Result 包装）")
        void insertPropagatesDataAccessExceptionAsIs() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            PgJdbcRepository<User, Long> repo =
                    new PgJdbcRepository<>(User.class, Long.class, jdbc, defaultsRegistry(), DIALECT);

            DataAccessException boom = new org.springframework.dao.DataIntegrityViolationException("duplicate key");
            when(jdbc.update(any(String.class), any(SqlParameterSource.class))).thenThrow(boom);

            User user = new User();
            user.id = 1L;

            // insert() 方法签名返回 void——异常是唯一的失败通信渠道，验证其为 DataAccessException 家族
            // 而非某种 Result<Void, Error> 返回值（后者会静默吞掉，方法调用不会抛出）。
            assertThatThrownBy(() -> repo.insert(user))
                    .isInstanceOf(DataAccessException.class)
                    .isSameAs(boom);
        }

        @Test
        @DisplayName("updateById 遇 DataAccessException 原样抛出（非收敛为 Result 返回值）")
        void updateByIdPropagatesDataAccessExceptionAsIs() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            PgJdbcRepository<User, Long> repo =
                    new PgJdbcRepository<>(User.class, Long.class, jdbc, defaultsRegistry(), DIALECT);

            DataAccessException boom = new org.springframework.dao.TransientDataAccessResourceException("connection lost");
            when(jdbc.update(any(String.class), any(SqlParameterSource.class))).thenThrow(boom);

            User user = new User();
            user.id = 1L;

            assertThatThrownBy(() -> repo.updateById(user))
                    .isInstanceOf(DataAccessException.class)
                    .isSameAs(boom);
        }

        @Test
        @DisplayName("findById 遇非 EmptyResult 的 DataAccessException 仍原样抛出（只收敛空结果这一种）")
        void findByIdPropagatesNonEmptyResultDataAccessExceptionAsIs() {
            NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
            PgJdbcRepository<User, Long> repo =
                    new PgJdbcRepository<>(User.class, Long.class, jdbc, defaultsRegistry(), DIALECT);

            DataAccessException boom = new org.springframework.dao.QueryTimeoutException("timeout", null);
            when(jdbc.queryForObject(any(String.class), any(MapSqlParameterSource.class), any(EntityRowMapper.class)))
                    .thenThrow(boom);

            assertThatThrownBy(() -> repo.findById(1L))
                    .isInstanceOf(DataAccessException.class)
                    .isSameAs(boom);
        }

        @Test
        @DisplayName("JdbcRepository 全部方法签名不含 Result 类型（编译期静态证据）")
        void noRepositoryMethodReturnsResultType() {
            for (var method : JdbcRepository.class.getDeclaredMethods()) {
                assertThat(method.getReturnType().getName())
                        .as("方法 %s 的返回类型不得是 Result", method.getName())
                        .doesNotContain("Result");
                for (Class<?> paramType : method.getParameterTypes()) {
                    assertThat(paramType.getName())
                            .as("方法 %s 的参数类型不得是 Result", method.getName())
                            .doesNotContain("cn.code91.facility.result");
                }
            }
        }
    }
}
