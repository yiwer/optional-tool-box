package cn.code91.toolbox.database.params;

import cn.code91.toolbox.database.annotation.Column;
import cn.code91.toolbox.database.annotation.Id;
import cn.code91.toolbox.database.annotation.Transient;
import cn.code91.toolbox.database.naming.ColumnNamingStrategy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EntityIntrospector} 反射实体字段，产出 {@link EntityMeta}：
 * 列名解析（{@code @Column.value} 优先，缺省走 {@link ColumnNamingStrategy}）、
 * {@code @Id} 标记、{@code @Transient} 跳过、静态字段跳过。
 */
@DisplayName("EntityIntrospector")
class EntityIntrospectorTest {

    static class User {
        static String ignoredStatic = "should not appear";
        @Id
        Long id;
        String userName;
        @Column("email_addr")
        String email;
        @Transient
        String displayName;
    }

    @Test
    @DisplayName("非 Transient/static 字段全部纳入，顺序与声明一致")
    void includesAllMappableFields() {
        EntityMeta meta = EntityIntrospector.of(User.class);

        assertThat(meta.fields()).extracting(FieldMapping::javaFieldName)
                .containsExactly("id", "userName", "email");
    }

    @Test
    @DisplayName("@Transient 字段被跳过")
    void skipsTransientField() {
        EntityMeta meta = EntityIntrospector.of(User.class);

        assertThat(meta.fields()).extracting(FieldMapping::javaFieldName)
                .doesNotContain("displayName");
    }

    @Test
    @DisplayName("static 字段被跳过")
    void skipsStaticField() {
        EntityMeta meta = EntityIntrospector.of(User.class);

        assertThat(meta.fields()).extracting(FieldMapping::javaFieldName)
                .doesNotContain("ignoredStatic");
    }

    @Test
    @DisplayName("无 @Column 时列名走命名策略推算")
    void columnNameFallsBackToNamingStrategy() {
        EntityMeta meta = EntityIntrospector.of(User.class);

        FieldMapping userName = meta.fields().stream()
                .filter(fm -> fm.javaFieldName().equals("userName")).findFirst().orElseThrow();
        assertThat(userName.sqlColumnName()).isEqualTo("user_name");
    }

    @Test
    @DisplayName("@Column.value 显式指定时优先于命名策略")
    void explicitColumnValueOverridesNamingStrategy() {
        EntityMeta meta = EntityIntrospector.of(User.class);

        FieldMapping email = meta.fields().stream()
                .filter(fm -> fm.javaFieldName().equals("email")).findFirst().orElseThrow();
        assertThat(email.sqlColumnName()).isEqualTo("email_addr");
    }

    @Test
    @DisplayName("@Id 字段标记 isId=true，其余为 false")
    void marksIdField() {
        EntityMeta meta = EntityIntrospector.of(User.class);

        FieldMapping id = meta.fields().stream()
                .filter(fm -> fm.javaFieldName().equals("id")).findFirst().orElseThrow();
        FieldMapping userName = meta.fields().stream()
                .filter(fm -> fm.javaFieldName().equals("userName")).findFirst().orElseThrow();
        assertThat(id.isId()).isTrue();
        assertThat(userName.isId()).isFalse();
    }

    @Test
    @DisplayName("field 句柄已 setAccessible(true)，可直接反射取值")
    void fieldHandleIsAccessible() throws IllegalAccessException {
        EntityMeta meta = EntityIntrospector.of(User.class);
        User user = new User();
        user.userName = "alice";

        FieldMapping userName = meta.fields().stream()
                .filter(fm -> fm.javaFieldName().equals("userName")).findFirst().orElseThrow();
        assertThat(userName.field().get(user)).isEqualTo("alice");
    }

    @Test
    @DisplayName("同一 class 反复调用走缓存，返回同一 EntityMeta 引用")
    void cachedPerClass() {
        EntityMeta first = EntityIntrospector.of(User.class);
        EntityMeta second = EntityIntrospector.of(User.class);

        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("自定义 ColumnNamingStrategy：不走缓存，按传入策略推算列名")
    void customStrategyBypassesCache() {
        EntityMeta identityMeta = EntityIntrospector.of(User.class, ColumnNamingStrategy.IDENTITY);

        FieldMapping userName = identityMeta.fields().stream()
                .filter(fm -> fm.javaFieldName().equals("userName")).findFirst().orElseThrow();
        assertThat(userName.sqlColumnName()).isEqualTo("userName");
    }

    static class NoAnnotations {
        int score;
    }

    @Test
    @DisplayName("完全无注解的实体：全部字段按命名策略映射，isId 全为 false")
    void entityWithoutAnnotations() {
        EntityMeta meta = EntityIntrospector.of(NoAnnotations.class);

        assertThat(meta.fields()).hasSize(1);
        assertThat(meta.fields().get(0).sqlColumnName()).isEqualTo("score");
        assertThat(meta.fields().get(0).isId()).isFalse();
    }
}
