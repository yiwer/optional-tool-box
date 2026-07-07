package cn.code91.toolbox.database.annotation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 注解体系的契约测试：RUNTIME 保留策略（反射期可读，装配/Task 8 的 SqlBuilder 依赖此契约）+
 * 默认值语义（{@code @Column.value} 缺省走命名策略，{@code sqlType} 缺省不路由特化 handler）。
 */
@DisplayName("annotation 包契约")
class AnnotationDefaultsTest {

    @Table("t_probe")
    static class Probe {
        @Id
        @Column("id")
        Long id;

        @Column
        String userName;

        @Column(sqlType = "jsonb")
        String profile;

        @Transient
        String displayName;
    }

    @Test
    @DisplayName("Table/Id/Column/Transient 均为 RUNTIME 保留")
    void allAnnotationsAreRuntimeRetained() {
        assertThat(Table.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(Id.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(Column.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
        assertThat(Transient.class.getAnnotation(Retention.class).value()).isEqualTo(RetentionPolicy.RUNTIME);
    }

    @Test
    @DisplayName("@Table.value 读出物理表名")
    void tableValueIsReadable() {
        assertThat(Probe.class.getAnnotation(Table.class).value()).isEqualTo("t_probe");
    }

    @Test
    @DisplayName("@Column 显式 value 可读出")
    void columnExplicitValueIsReadable() throws NoSuchFieldException {
        Field field = Probe.class.getDeclaredField("id");
        assertThat(field.getAnnotation(Column.class).value()).isEqualTo("id");
    }

    @Test
    @DisplayName("@Column 缺省 value 为空串（回退命名策略的信号）")
    void columnDefaultValueIsEmpty() throws NoSuchFieldException {
        Field field = Probe.class.getDeclaredField("userName");
        assertThat(field.getAnnotation(Column.class).value()).isEmpty();
    }

    @Test
    @DisplayName("@Column 缺省 sqlType 为空串（不路由特化 handler 的信号）")
    void columnDefaultSqlTypeIsEmpty() throws NoSuchFieldException {
        Field field = Probe.class.getDeclaredField("userName");
        assertThat(field.getAnnotation(Column.class).sqlType()).isEmpty();
    }

    @Test
    @DisplayName("@Column 显式 sqlType 可读出")
    void columnExplicitSqlTypeIsReadable() throws NoSuchFieldException {
        Field field = Probe.class.getDeclaredField("profile");
        assertThat(field.getAnnotation(Column.class).sqlType()).isEqualTo("jsonb");
    }

    @Test
    @DisplayName("@Id/@Transient 无属性，仅标记")
    void idAndTransientAreMarkerOnly() throws NoSuchFieldException {
        assertThat(Probe.class.getDeclaredField("id").getAnnotation(Id.class)).isNotNull();
        assertThat(Probe.class.getDeclaredField("displayName").getAnnotation(Transient.class)).isNotNull();
    }
}
