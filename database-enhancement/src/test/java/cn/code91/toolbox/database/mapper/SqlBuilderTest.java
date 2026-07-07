package cn.code91.toolbox.database.mapper;

import cn.code91.toolbox.database.annotation.Column;
import cn.code91.toolbox.database.annotation.Id;
import cn.code91.toolbox.database.annotation.Table;
import cn.code91.toolbox.database.annotation.Transient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SqlBuilder} 四件套 SQL 文本生成：列序、占位符（用 SQL 列名）、命名策略生效、
 * {@code @Table}/{@code @Id} 缺失守卫、缓存 identity-stable。
 */
@DisplayName("SqlBuilder - 4 by-id CRUD SQL 生成")
class SqlBuilderTest {

    @Table("users")
    static class User {
        @Id
        Long id;
        String userName;
        @Column("email_addr")
        String email;
        @Transient
        String passwordPlain;
    }

    static class NoTableEntity {              // 类名 fallback
        @Id
        Long id;
        String value;
    }

    static class UserAccount {                // CamelCase fallback → user_account
        @Id
        Long id;
        String label;
    }

    static class NoIdEntity {                 // 0 @Id 字段
        String onlyField;
    }

    static class MultiIdEntity {              // ≥2 @Id 字段
        @Id
        Long idA;
        @Id
        Long idB;
    }

    static class AllTransientEntity {         // 全 @Transient
        @Transient
        String x;
        @Transient
        String y;
    }

    @Nested
    @DisplayName("guards")
    class Guards {
        @Test
        @DisplayName("0 @Id + updateById 抛 ISE")
        void noIdUpdateFails() {
            assertThatThrownBy(() -> SqlBuilder.updateById(NoIdEntity.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@Id")
                    .hasMessageContaining("UPDATE_BY_ID");
        }

        @Test
        @DisplayName("0 @Id + selectById 抛 ISE")
        void noIdSelectFails() {
            assertThatThrownBy(() -> SqlBuilder.selectById(NoIdEntity.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@Id")
                    .hasMessageContaining("SELECT_BY_ID");
        }

        @Test
        @DisplayName("0 @Id + deleteById 抛 ISE")
        void noIdDeleteFails() {
            assertThatThrownBy(() -> SqlBuilder.deleteById(NoIdEntity.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@Id")
                    .hasMessageContaining("DELETE_BY_ID");
        }

        @Test
        @DisplayName("≥2 @Id + 任何 op 抛 ISE（含 @Id 关键字）")
        void multiIdAllOpsFail() {
            assertThatThrownBy(() -> SqlBuilder.insert(MultiIdEntity.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@Id");
            assertThatThrownBy(() -> SqlBuilder.updateById(MultiIdEntity.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@Id");
            assertThatThrownBy(() -> SqlBuilder.selectById(MultiIdEntity.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@Id");
            assertThatThrownBy(() -> SqlBuilder.deleteById(MultiIdEntity.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@Id");
        }

        @Test
        @DisplayName("全 @Transient (0 mappable fields) + 任何 op 抛 ISE")
        void allTransientFails() {
            assertThatThrownBy(() -> SqlBuilder.insert(AllTransientEntity.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("mappable");
        }

        @Test
        @DisplayName("@Table.value 为空串 → ISE")
        void tableValueEmptyFails() {
            @Table("")
            class BadTableEntity {
                @Id
                Long id;
            }
            assertThatThrownBy(() -> SqlBuilder.insert(BadTableEntity.class))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("@Table");
        }
    }

    @Nested
    @DisplayName("insert")
    class Insert {
        @Test
        @DisplayName("INSERT 列出全部非 @Transient 字段（含 @Id），占位符 = 列名")
        void insertEmitsAllNonTransientColumnsWithColumnNamePlaceholders() {
            String sql = SqlBuilder.insert(User.class);
            assertThat(sql).isEqualTo(
                    "INSERT INTO users (id, user_name, email_addr) "
                            + "VALUES (:id, :user_name, :email_addr)");
        }

        @Test
        @DisplayName("@Transient 字段被排除（passwordPlain 不出现）")
        void insertExcludesTransient() {
            String sql = SqlBuilder.insert(User.class);
            assertThat(sql).doesNotContain("passwordPlain").doesNotContain("password_plain");
        }

        @Test
        @DisplayName("无 @Table 时类名 fallback：NoTableEntity → no_table_entity")
        void insertFallsBackSingleWordClassName() {
            String sql = SqlBuilder.insert(NoTableEntity.class);
            assertThat(sql).startsWith("INSERT INTO no_table_entity ");
        }

        @Test
        @DisplayName("无 @Table 时类名 fallback：UserAccount → user_account")
        void insertFallsBackCamelCaseClassName() {
            String sql = SqlBuilder.insert(UserAccount.class);
            assertThat(sql).startsWith("INSERT INTO user_account ");
        }

        @Test
        @DisplayName("0 @Id 时 insert 仍可（NoIdEntity 列出 onlyField 列）")
        void insertNoIdEntityListsAllColumns() {
            String sql = SqlBuilder.insert(NoIdEntity.class);
            assertThat(sql).isEqualTo(
                    "INSERT INTO no_id_entity (only_field) VALUES (:only_field)");
        }
    }

    @Nested
    @DisplayName("selectById")
    class SelectById {
        @Test
        @DisplayName("SELECT 列同 INSERT（同序），WHERE id = :id")
        void selectByIdColumnsMatchInsertOrder() {
            String sql = SqlBuilder.selectById(User.class);
            assertThat(sql).isEqualTo(
                    "SELECT id, user_name, email_addr FROM users WHERE id = :id");
        }

        @Test
        @DisplayName("自定义 id 列名（@Column 改名）跟随到 WHERE")
        void selectByIdRespectsCustomIdColumnName() {
            @Table("legacy_users")
            class LegacyUser {
                @Id
                @Column("user_pk")
                Long id;
                String label;
            }
            String sql = SqlBuilder.selectById(LegacyUser.class);
            assertThat(sql).isEqualTo(
                    "SELECT user_pk, label FROM legacy_users WHERE user_pk = :user_pk");
        }
    }

    @Nested
    @DisplayName("updateById")
    class UpdateById {
        @Test
        @DisplayName("SET 排除 @Id 列；WHERE id = :id")
        void updateByIdSetExcludesIdWhereIncludesId() {
            String sql = SqlBuilder.updateById(User.class);
            assertThat(sql).isEqualTo(
                    "UPDATE users SET user_name = :user_name, email_addr = :email_addr "
                            + "WHERE id = :id");
        }

        @Test
        @DisplayName("自定义 id 列名 + 仅 1 个非 id 字段")
        void updateByIdSingleNonIdField() {
            @Table("logs")
            class Log {
                @Id
                @Column("log_pk")
                Long id;
                String message;
            }
            String sql = SqlBuilder.updateById(Log.class);
            assertThat(sql).isEqualTo(
                    "UPDATE logs SET message = :message WHERE log_pk = :log_pk");
        }
    }

    @Nested
    @DisplayName("deleteById")
    class DeleteById {
        @Test
        @DisplayName("DELETE WHERE id = :id（标准 entity）")
        void deleteByIdWhereOnly() {
            String sql = SqlBuilder.deleteById(User.class);
            assertThat(sql).isEqualTo("DELETE FROM users WHERE id = :id");
        }

        @Test
        @DisplayName("自定义 id 列名")
        void deleteByIdCustomIdCol() {
            @Table("logs")
            class Log {
                @Id
                @Column("log_pk")
                Long id;
                String message;
            }
            String sql = SqlBuilder.deleteById(Log.class);
            assertThat(sql).isEqualTo("DELETE FROM logs WHERE log_pk = :log_pk");
        }
    }

    @Nested
    @DisplayName("cache (identity-stable)")
    class Cache {
        @Test
        @DisplayName("同 class + 同 op 反复调用返回同一 String 引用（==）")
        void identityStableReturn() {
            String s1 = SqlBuilder.insert(User.class);
            String s2 = SqlBuilder.insert(User.class);
            assertThat(s1).isSameAs(s2);

            String u1 = SqlBuilder.updateById(User.class);
            String u2 = SqlBuilder.updateById(User.class);
            assertThat(u1).isSameAs(u2);
        }
    }
}
