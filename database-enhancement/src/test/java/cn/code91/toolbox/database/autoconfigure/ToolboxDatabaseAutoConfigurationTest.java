package cn.code91.toolbox.database.autoconfigure;

import cn.code91.toolbox.database.dialect.PostgresDialect;
import cn.code91.toolbox.database.dialect.SqlDialect;
import cn.code91.toolbox.database.naming.ColumnNamingStrategy;
import cn.code91.toolbox.database.observability.P6spyDataSourceWrappingBeanPostProcessor;
import cn.code91.toolbox.database.registry.FieldHandlerRegistry;
import cn.code91.toolbox.database.registry.FieldHandlerRegistryCustomizer;
import cn.code91.toolbox.database.spi.FieldHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配矩阵（Task 7 brief 测试小节）：无 {@code NamedParameterJdbcTemplate} 类 → 全不装配；
 * p6spy 缺类/关开关 → BPP 不装配；三态齐全 → BPP 装配；用户覆盖 Seam bean 让位。
 */
@DisplayName("ToolboxDatabaseAutoConfiguration")
class ToolboxDatabaseAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class, ToolboxDatabaseAutoConfiguration.class))
            .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:toolbox-database-autoconfig-test",
                    "spring.datasource.username=sa");

    @Nested
    @DisplayName("默认装配")
    class DefaultAssembly {

        @Test
        @DisplayName("装配 ColumnNamingStrategy（CAMEL_TO_UNDERSCORE）")
        void assemblesDefaultColumnNamingStrategy() {
            contextRunner.run(context -> {
                assertThat(context).hasSingleBean(ColumnNamingStrategy.class);
                assertThat(context.getBean(ColumnNamingStrategy.class)).isSameAs(ColumnNamingStrategy.CAMEL_TO_UNDERSCORE);
            });
        }

        @Test
        @DisplayName("装配 SqlDialect（P1 唯一实现 PostgresDialect）")
        void assemblesPostgresDialectByDefault() {
            contextRunner.run(context -> {
                assertThat(context).hasSingleBean(SqlDialect.class);
                assertThat(context.getBean(SqlDialect.class)).isInstanceOf(PostgresDialect.class);
            });
        }

        @Test
        @DisplayName("装配 FieldHandlerRegistry 且已 freeze")
        void assemblesFrozenFieldHandlerRegistry() {
            contextRunner.run(context -> {
                assertThat(context).hasSingleBean(FieldHandlerRegistry.class);
                FieldHandlerRegistry registry = context.getBean(FieldHandlerRegistry.class);
                assertThat(registry.isFrozen()).isTrue();
                assertThat(registry.findHandler(String.class)).isPresent();
                // PostgresDialect.registerBuiltins 已在装配期调用：uuid 的 sqlType 别名应可路由
                assertThat(registry.findHandlerForSqlType("jsonb", Object.class)).isPresent();
            });
        }

        @Test
        @DisplayName("p6spy.enabled 未配置（默认 false）：不装配 BeanPostProcessor")
        void doesNotAssembleP6spyBppByDefault() {
            contextRunner.run(context ->
                    assertThat(context.getBeansOfType(BeanPostProcessor.class).values())
                            .noneMatch(P6spyDataSourceWrappingBeanPostProcessor.class::isInstance));
        }
    }

    @Nested
    @DisplayName("总开关 L2")
    class ModuleToggle {

        @Test
        @DisplayName("toolbox.database.enabled=false：全不装配")
        void enabledFalseAssemblesNothing() {
            contextRunner.withPropertyValues("toolbox.database.enabled=false")
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(ColumnNamingStrategy.class);
                        assertThat(context).doesNotHaveBean(SqlDialect.class);
                        assertThat(context).doesNotHaveBean(FieldHandlerRegistry.class);
                    });
        }
    }

    @Nested
    @DisplayName("L1：NamedParameterJdbcTemplate 缺类")
    class MissingNamedParameterJdbcTemplate {

        @Test
        @DisplayName("classpath 无 NamedParameterJdbcTemplate：整个模块不装配")
        void assembliesNothingWhenNamedParameterJdbcTemplateMissing() {
            contextRunner
                    .withClassLoader(new FilteredClassLoader(NamedParameterJdbcTemplate.class))
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(ColumnNamingStrategy.class);
                        assertThat(context).doesNotHaveBean(SqlDialect.class);
                        assertThat(context).doesNotHaveBean(FieldHandlerRegistry.class);
                    });
        }
    }

    @Nested
    @DisplayName("p6spy BPP 装配三态")
    class P6spyAssembly {

        @Test
        @DisplayName("enabled=true 且 P6DataSource 在 classpath：BPP 装配")
        void assemblesBppWhenEnabledAndClassPresent() {
            contextRunner.withPropertyValues("toolbox.database.p6spy.enabled=true")
                    .run(context -> assertThat(context).hasSingleBean(P6spyDataSourceWrappingBeanPostProcessor.class));
        }

        @Test
        @DisplayName("enabled=true 但 P6DataSource 缺类：BPP 不装配")
        void doesNotAssembleBppWhenClassMissingEvenIfEnabled() {
            contextRunner
                    .withClassLoader(new FilteredClassLoader("com.p6spy.engine.spy.P6DataSource"))
                    .withPropertyValues("toolbox.database.p6spy.enabled=true")
                    .run(context -> assertThat(context).doesNotHaveBean(P6spyDataSourceWrappingBeanPostProcessor.class));
        }

        @Test
        @DisplayName("enabled=false（显式）：BPP 不装配")
        void doesNotAssembleBppWhenExplicitlyDisabled() {
            contextRunner.withPropertyValues("toolbox.database.p6spy.enabled=false")
                    .run(context -> assertThat(context).doesNotHaveBean(P6spyDataSourceWrappingBeanPostProcessor.class));
        }
    }

    @Nested
    @DisplayName("Seam：用户覆盖让位")
    class UserOverrides {

        @Test
        @DisplayName("用户自定义 ColumnNamingStrategy 生效，装配层让位")
        void userColumnNamingStrategyTakesPrecedence() {
            contextRunner.withBean(ColumnNamingStrategy.class, () -> ColumnNamingStrategy.IDENTITY)
                    .run(context -> assertThat(context.getBean(ColumnNamingStrategy.class)).isSameAs(ColumnNamingStrategy.IDENTITY));
        }

        @Test
        @DisplayName("用户自定义 SqlDialect 生效，装配层让位")
        void userSqlDialectTakesPrecedence() {
            SqlDialect stub = new SqlDialect() {
                @Override
                public String name() {
                    return "stub";
                }

                @Override
                public String limitOffset(String sql, long limit, long offset) {
                    return sql;
                }

                @Override
                public void registerBuiltins(FieldHandlerRegistry registry) {
                    // no-op
                }
            };
            contextRunner.withBean(SqlDialect.class, () -> stub)
                    .run(context -> assertThat(context.getBean(SqlDialect.class)).isSameAs(stub));
        }

        @Test
        @DisplayName("用户自定义 FieldHandlerRegistry 生效，装配层让位（不会被再次 freeze 覆盖内容）")
        void userFieldHandlerRegistryTakesPrecedence() {
            FieldHandlerRegistry custom = new FieldHandlerRegistry();
            contextRunner.withBean(FieldHandlerRegistry.class, () -> custom)
                    .run(context -> assertThat(context.getBean(FieldHandlerRegistry.class)).isSameAs(custom));
        }

        @Test
        @DisplayName("用户暴露的 FieldHandler bean 被收集进默认 registry")
        void userFieldHandlerBeanIsCollectedIntoDefaultRegistry() {
            contextRunner.withUserConfiguration(CustomHandlerConfig.class)
                    .run(context -> {
                        FieldHandlerRegistry registry = context.getBean(FieldHandlerRegistry.class);
                        assertThat(registry.findHandler(CustomHandlerConfig.Marker.class)).isPresent();
                    });
        }

        @Test
        @DisplayName("用户暴露的 FieldHandlerRegistryCustomizer 在 freeze 前被调用")
        void userCustomizerIsAppliedBeforeFreeze() {
            contextRunner.withUserConfiguration(CustomizerConfig.class)
                    .run(context -> {
                        FieldHandlerRegistry registry = context.getBean(FieldHandlerRegistry.class);
                        assertThat(registry.findHandler(CustomizerConfig.Marker.class)).isPresent();
                    });
        }
    }

    @Configuration
    static class CustomHandlerConfig {
        static final class Marker {
        }

        @Bean
        FieldHandler<Marker> markerHandler() {
            return new FieldHandler<>() {
                @Override
                public Class<Marker> javaType() {
                    return Marker.class;
                }

                @Override
                public Marker read(ResultSet rs, int columnIndex) {
                    return new Marker();
                }

                @Override
                public Object write(Marker value) {
                    return value;
                }
            };
        }
    }

    @Configuration
    static class CustomizerConfig {
        static final class Marker {
        }

        @Bean
        FieldHandlerRegistryCustomizer markerCustomizer() {
            return registry -> registry.register(new FieldHandler<Marker>() {
                @Override
                public Class<Marker> javaType() {
                    return Marker.class;
                }

                @Override
                public Marker read(ResultSet rs, int columnIndex) {
                    return new Marker();
                }

                @Override
                public Object write(Marker value) {
                    return value;
                }
            });
        }
    }
}
