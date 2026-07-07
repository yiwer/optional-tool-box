package cn.code91.toolbox.database.integration;

import cn.code91.toolbox.database.annotation.Column;
import cn.code91.toolbox.database.annotation.Id;
import cn.code91.toolbox.database.annotation.Table;
import cn.code91.toolbox.database.dialect.PostgresDialect;
import cn.code91.toolbox.database.dialect.SqlDialect;
import cn.code91.toolbox.database.params.AnnotatedParameterSource;
import cn.code91.toolbox.database.registry.FieldHandlerRegistry;
import cn.code91.toolbox.database.repository.PgJdbcRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>PgJdbcRepository 全 7 op 真库回环（Testcontainers PostgreSQL）</b>
 * <p>实体含 jsonb/uuid/array 字段（真实驱动类型往返，不能仅靠 mock 断言）：
 * insert → findById → updateById → list → count → existsById → deleteById。
 * 另附 L1 手写 SQL 与 L2 生成 SQL 共用同一 {@link AnnotatedParameterSource} 双 key 的联合验证。</p>
 */
@DisplayName("PgJdbcRepository 全 op 真库回环（IT）")
class PgJdbcRepositoryIT extends AbstractPostgresIntegrationTest {

    @Table("it_widget")
    static class Widget {
        @Id
        Long id;
        String widgetName;
        @Column(value = "profile", sqlType = "jsonb")
        Profile profile;
        @Column(sqlType = "uuid")
        UUID externalRef;
        @Column(value = "tags", sqlType = "text[]")
        String[] tags;

        Widget() {
        }

        Widget(Long id, String widgetName, Profile profile, UUID externalRef, String[] tags) {
            this.id = id;
            this.widgetName = widgetName;
            this.profile = profile;
            this.externalRef = externalRef;
            this.tags = tags;
        }
    }

    record Profile(String color, int weight) {
    }

    private static NamedParameterJdbcTemplate jdbc;
    private static FieldHandlerRegistry registry;
    private static SqlDialect dialect;
    private static PgJdbcRepository<Widget, Long> repository;

    @BeforeAll
    static void setUpRepository() throws SQLException {
        Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS it_widget (
                        id BIGINT PRIMARY KEY,
                        widget_name TEXT,
                        profile JSONB,
                        external_ref UUID,
                        tags TEXT[]
                    )
                    """);
        }

        SingleConnectionDataSource dataSource = new SingleConnectionDataSource(connection, false);
        jdbc = new NamedParameterJdbcTemplate(dataSource);

        registry = new FieldHandlerRegistry();
        FieldHandlerRegistry.registerBuiltins(registry);
        dialect = new PostgresDialect();
        dialect.registerBuiltins(registry);
        registry.freeze();

        repository = new PgJdbcRepository<>(Widget.class, Long.class, jdbc, registry, dialect);
    }

    @BeforeEach
    void truncate() {
        jdbc.getJdbcTemplate().execute("TRUNCATE TABLE it_widget");
    }

    @Test
    @DisplayName("insert → findById：jsonb/uuid/array 字段真实往返")
    void insertThenFindByIdRoundTripsRichTypes() {
        Profile profile = new Profile("blue", 42);
        UUID externalRef = UUID.randomUUID();
        Widget widget = new Widget(1L, "gizmo", profile, externalRef, new String[]{"alpha", "beta"});

        repository.insert(widget);
        Optional<Widget> found = repository.findById(1L);

        assertThat(found).isPresent();
        Widget result = found.get();
        assertThat(result.id).isEqualTo(1L);
        assertThat(result.widgetName).isEqualTo("gizmo");
        assertThat(result.profile).isEqualTo(profile);
        assertThat(result.externalRef).isEqualTo(externalRef);
        assertThat(result.tags).containsExactly("alpha", "beta");
    }

    @Test
    @DisplayName("findById 未命中返回 Optional.empty")
    void findByIdReturnsEmptyWhenAbsent() {
        assertThat(repository.findById(999L)).isEmpty();
    }

    @Test
    @DisplayName("updateById：修改后真库可读回新值")
    void updateByIdPersistsChanges() {
        Widget original = new Widget(2L, "widget-v1", new Profile("red", 1), UUID.randomUUID(),
                new String[]{"x"});
        repository.insert(original);

        Widget updated = new Widget(2L, "widget-v2", new Profile("green", 2), original.externalRef,
                new String[]{"y", "z"});
        int affected = repository.updateById(updated);

        assertThat(affected).isEqualTo(1);
        Widget reloaded = repository.findById(2L).orElseThrow();
        assertThat(reloaded.widgetName).isEqualTo("widget-v2");
        assertThat(reloaded.profile).isEqualTo(new Profile("green", 2));
        assertThat(reloaded.tags).containsExactly("y", "z");
    }

    @Test
    @DisplayName("updateById：不存在的 id 返回 0 affected rows")
    void updateByIdReturnsZeroWhenAbsent() {
        Widget ghost = new Widget(9999L, "ghost", new Profile("none", 0), UUID.randomUUID(), new String[]{});
        assertThat(repository.updateById(ghost)).isEqualTo(0);
    }

    @Test
    @DisplayName("existsById：插入前 false，插入后 true")
    void existsByIdReflectsPresence() {
        assertThat(repository.existsById(3L)).isFalse();

        repository.insert(new Widget(3L, "exists-test", new Profile("black", 0), UUID.randomUUID(),
                new String[]{}));

        assertThat(repository.existsById(3L)).isTrue();
    }

    @Test
    @DisplayName("count：反映真实插入行数")
    void countReflectsRowCount() {
        assertThat(repository.count()).isEqualTo(0L);

        repository.insert(new Widget(10L, "a", new Profile("c1", 1), UUID.randomUUID(), new String[]{}));
        repository.insert(new Widget(11L, "b", new Profile("c2", 2), UUID.randomUUID(), new String[]{}));

        assertThat(repository.count()).isEqualTo(2L);
    }

    @Test
    @DisplayName("list：按 SqlDialect.limitOffset 分页，ORDER BY id 排序")
    void listPaginatesInIdOrder() {
        for (long i = 1; i <= 5; i++) {
            repository.insert(new Widget(i, "item-" + i, new Profile("c", (int) i), UUID.randomUUID(),
                    new String[]{}));
        }

        List<Widget> page1 = repository.list(2, 0);
        List<Widget> page2 = repository.list(2, 2);

        assertThat(page1).extracting(w -> w.id).containsExactly(1L, 2L);
        assertThat(page2).extracting(w -> w.id).containsExactly(3L, 4L);
    }

    @Test
    @DisplayName("deleteById：删除后 findById 不再命中，count 相应减少")
    void deleteByIdRemovesRow() {
        repository.insert(new Widget(20L, "to-delete", new Profile("d", 0), UUID.randomUUID(),
                new String[]{}));
        assertThat(repository.count()).isEqualTo(1L);

        int affected = repository.deleteById(20L);

        assertThat(affected).isEqualTo(1);
        assertThat(repository.findById(20L)).isEmpty();
        assertThat(repository.count()).isEqualTo(0L);
    }

    @Test
    @DisplayName("完整回环：insert → findById → updateById → list → count → existsById → deleteById")
    void fullSevenOpRoundTrip() {
        Widget widget = new Widget(30L, "roundtrip", new Profile("gold", 100), UUID.randomUUID(),
                new String[]{"one", "two", "three"});

        repository.insert(widget);
        assertThat(repository.findById(30L)).isPresent();

        Widget updated = new Widget(30L, "roundtrip-updated", new Profile("silver", 200), widget.externalRef,
                new String[]{"four"});
        assertThat(repository.updateById(updated)).isEqualTo(1);

        assertThat(repository.list(10, 0)).extracting(w -> w.id).contains(30L);
        assertThat(repository.count()).isEqualTo(1L);
        assertThat(repository.existsById(30L)).isTrue();
        assertThat(repository.deleteById(30L)).isEqualTo(1);
        assertThat(repository.existsById(30L)).isFalse();
    }

    @Test
    @DisplayName("L1 手写 SQL 与 L2 生成 SQL 共用同一 AnnotatedParameterSource 的双 key 联合验证")
    void handwrittenSqlAndGeneratedSqlShareSameParameterSource() {
        Widget widget = new Widget(40L, "shared-ps", new Profile("cyan", 7), UUID.randomUUID(),
                new String[]{"tag1"});

        // L2：SqlBuilder 生成的 insert（占位符用 SQL 列名，如 :widget_name）经 PgJdbcRepository.insert 执行。
        repository.insert(widget);

        // L1：手写 SQL 直接使用 Java 字段名占位符（如 :widgetName），
        // 复用同一个 AnnotatedParameterSource 实例——证明双 key 让手写 SQL 与生成 SQL 互操作。
        var handwrittenParams = AnnotatedParameterSource.of(widget, registry);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM it_widget WHERE id = :id AND widget_name = :widgetName",
                handwrittenParams, Long.class);

        assertThat(count).isEqualTo(1L);

        // 同一参数源也可用 SQL 列名 key（:widget_name）命中，验证双 key 双向可用。
        Long countByColumnKey = jdbc.queryForObject(
                "SELECT COUNT(*) FROM it_widget WHERE id = :id AND widget_name = :widget_name",
                handwrittenParams, Long.class);

        assertThat(countByColumnKey).isEqualTo(1L);
    }
}
