package cn.code91.toolbox.auth.autoconfigure;

import cn.code91.toolbox.auth.jwt.KeycloakJwtAuthenticationConverter;
import cn.code91.toolbox.auth.web.SecurityExceptionAdvice;
import cn.code91.toolbox.auth.web.SessionUserBridgeFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1-L4 装配矩阵（07 §9 装配行）。WebApplicationContextRunner 叠加 SecurityAutoConfiguration
 * 以获得 HttpSecurity 原型 bean（Boot 官方装配测试同法）；叠加 WebMvcAutoConfiguration 供
 * {@code mvcHandlerMappingIntrospector} bean——默认链的 {@code .cors(Customizer.withDefaults())}
 * 与测试自建链的 {@code HttpSecurity#securityMatcher(String)} 均经 MVC-aware matcher 路径，
 * 真实消费方经 starter-web 全量 MVC 自动装配自然具备，此处独立 runner 需显式叠加对齐
 * （环境性缺口，非生产语义——同 SecurityAutoConfiguration 叠加先例）。
 */
@DisplayName("ToolboxAuthAutoConfiguration 装配矩阵")
class ToolboxAuthAutoConfigurationTest {

    private static final String[] MINIMAL = {
            "toolbox.auth.keycloak.server-url=https://kc.example.com",
            "toolbox.auth.keycloak.realm=demo" };

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolboxAuthAutoConfiguration.class,
                    SecurityAutoConfiguration.class, WebMvcAutoConfiguration.class));

    @Test
    void assemblesWithMinimalConfig() {
        webRunner.withPropertyValues(MINIMAL).run(context -> {
            assertThat(context).as("最小配置全量装配").hasNotFailed();
            assertThat(context).hasSingleBean(JwtDecoder.class);
            assertThat(context).hasSingleBean(KeycloakJwtAuthenticationConverter.class);
            assertThat(context).hasSingleBean(SecurityFilterChain.class);
            assertThat(context).as("模块链配置激活（与 chainSubSwitch 关侧嵌套类断言对称，A 轮终审滚存 #4）")
                    .hasSingleBean(ToolboxAuthAutoConfiguration.SecurityChainConfiguration.class);
            assertThat(context).hasBean("toolboxAuthMessageSource");
        });
    }

    @Test
    void missingRequiredConfigFailsStartupWithGuidance() {
        webRunner.run(context -> {
            assertThat(context).as("R5：缺必填启动失败（ADR-0018 有意偏离，07 §5.4）").hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(rootMessage(context.getStartupFailure()))
                    .as("失败消息带配置引导")
                    .contains("toolbox.auth.keycloak.server-url")
                    .contains("toolbox.auth.enabled=false");
        });
    }

    @Test
    void disabledSwitchBacksOffEntirely() {
        webRunner.withPropertyValues("toolbox.auth.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).as("L2 关停零装配").doesNotHaveBean(JwtDecoder.class);
            assertThat(context).doesNotHaveBean(KeycloakJwtAuthenticationConverter.class);
        });
    }

    @Test
    void missingSecurityClassesBackOff() {
        webRunner.withPropertyValues(MINIMAL)
                .withClassLoader(new FilteredClassLoader(JwtDecoder.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).as("L1：缺 oauth2-jose 类零装配")
                            .doesNotHaveBean(KeycloakJwtAuthenticationConverter.class);
                });
    }

    @Test
    void nonServletEnvironmentBacksOff() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolboxAuthAutoConfiguration.class))
                .withPropertyValues(MINIMAL)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).as("非 Servlet 环境零装配")
                            .doesNotHaveBean(KeycloakJwtAuthenticationConverter.class);
                });
    }

    @Test
    void userChainWinsAndModuleChainBacksOff() {
        webRunner.withPropertyValues(MINIMAL)
                .withUserConfiguration(UserChainConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).as("Seam 1：用户整链替换").hasSingleBean(SecurityFilterChain.class);
                    assertThat(context.getBean(SecurityFilterChain.class))
                            .isSameAs(context.getBean(UserChainConfig.class).chain);
                    assertThat(context).as("decoder/converter 仍在，供自建链复用（07 §5.3 指引）")
                            .hasSingleBean(JwtDecoder.class);
                });
    }

    @Test
    void userDecoderAndConverterWinOverModuleDefaults() {
        webRunner.withPropertyValues(MINIMAL)
                .withUserConfiguration(UserSeamBeansConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JwtDecoder.class))
                            .as("Seam 2：用户 decoder 退让模块默认件")
                            .isSameAs(context.getBean(UserSeamBeansConfig.class).decoder);
                    assertThat(context.getBean(KeycloakJwtAuthenticationConverter.class))
                            .as("Seam 3：用户 converter 退让模块默认件")
                            .isSameAs(context.getBean(UserSeamBeansConfig.class).converter);
                });
    }

    @Test
    void chainSubSwitchLeavesOnlyDecoderAndConverter() {
        webRunner.withPropertyValues(MINIMAL)
                .withPropertyValues("toolbox.auth.security-chain.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // 子开关只管本模块的链翼；测试 runner 为取 HttpSecurity 原型叠加了
                    // SecurityAutoConfiguration，此时无人认领 SecurityFilterChain 插槽，
                    // Boot 官方 defaultSecurityFilterChain 按其自身 @ConditionalOnMissingBean
                    // 语义正常补位——这是真实 Boot 退让语义，不是本模块的产物，故不断言
                    // "全局零 SecurityFilterChain"，而以嵌套配置类缺席断言"模块链翼整体不激活"
                    // （类引用抗重构，P1 账本 T5 Minor：字符串 bean 名断言在 bean 方法改名后
                    // doesNotHaveBean(不存在的名) 恒真、静默空过）。
                    assertThat(context).as("Seam 1 子开关：模块链配置整体不激活")
                            .doesNotHaveBean(ToolboxAuthAutoConfiguration.SecurityChainConfiguration.class);
                    assertThat(context).as("decoder/converter 保留（07 §5.3 关链不关件）")
                            .hasSingleBean(JwtDecoder.class);
                    assertThat(context).hasSingleBean(KeycloakJwtAuthenticationConverter.class);
                });
    }

    @Test
    void bridgeSwitchAddsFilterToChain() {
        webRunner.withPropertyValues(MINIMAL).run(context -> {
            var filters = ((DefaultSecurityFilterChain) context.getBean(SecurityFilterChain.class)).getFilters();
            assertThat(filters).as("R3 默认关：链中无桥接 filter")
                    .noneMatch(f -> f instanceof SessionUserBridgeFilter);
        });
        webRunner.withPropertyValues(MINIMAL)
                .withPropertyValues("toolbox.auth.bridge.session-user.enabled=true")
                .run(context -> {
                    var filters = ((DefaultSecurityFilterChain) context.getBean(SecurityFilterChain.class)).getFilters();
                    assertThat(filters).as("开关开启后桥接 filter 入链")
                            .anyMatch(f -> f instanceof SessionUserBridgeFilter);
                });
    }

    @Test
    void methodSecuritySwitchControlsNestedConfig() {
        webRunner.withPropertyValues(MINIMAL).run(context ->
                assertThat(context).hasSingleBean(ToolboxAuthAutoConfiguration.MethodSecurityConfiguration.class));
        webRunner.withPropertyValues(MINIMAL)
                .withPropertyValues("toolbox.auth.method-security.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ToolboxAuthAutoConfiguration.MethodSecurityConfiguration.class));
    }

    @Test
    void duplicateEnableMethodSecurityDeclarationIsIdempotent() {
        // 07 §10 幂等钉（P1 账本 T5 Minor/终审 #10）：消费方自行 @EnableMethodSecurity 时与
        // 模块 MethodSecurityConfiguration 构成双重声明——@Import 的基础设施配置类按类去重、
        // 同名基础设施 bean 覆盖注册，装配不得冲突失败。该假设依赖 Spring Security 装配内部
        // 行为，升级大版本前以本钉复核（07 §10 触发条件）。
        webRunner.withPropertyValues(MINIMAL)
                .withUserConfiguration(UserMethodSecurityConfig.class)
                .run(context -> {
                    assertThat(context).as("双重 @EnableMethodSecurity 装配不失败（幂等）").hasNotFailed();
                    assertThat(context).as("模块侧 method-security 配置仍在场（开关语义不受用户重复声明影响）")
                            .hasSingleBean(ToolboxAuthAutoConfiguration.MethodSecurityConfiguration.class);
                });
    }

    @Test
    void securityExceptionAdviceAssembledAndOverridable() {
        webRunner.withPropertyValues(MINIMAL).run(context ->
                assertThat(context).as("方法级安全异常出口默认装配（07 §4.3 补件）")
                        .hasSingleBean(SecurityExceptionAdvice.class));
        webRunner.withPropertyValues(MINIMAL)
                .withUserConfiguration(UserAdviceConfig.class)
                .run(context -> assertThat(context.getBean(SecurityExceptionAdvice.class))
                        .as("L4：用户自有 advice 退让模块默认件")
                        .isSameAs(context.getBean(UserAdviceConfig.class).advice));
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return String.valueOf(cur.getMessage());
    }

    @Configuration(proxyBeanMethods = false)
    static class UserChainConfig {
        SecurityFilterChain chain;

        @Bean
        SecurityFilterChain customChain(HttpSecurity http) throws Exception {
            chain = http.securityMatcher("/custom/**").build();
            return chain;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserAdviceConfig {
        SecurityExceptionAdvice advice = new SecurityExceptionAdvice();

        @Bean
        SecurityExceptionAdvice userAdvice() {
            return advice;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserSeamBeansConfig {
        JwtDecoder decoder = token -> { throw new UnsupportedOperationException("test stub"); };
        KeycloakJwtAuthenticationConverter converter =
                new KeycloakJwtAuthenticationConverter(false, java.util.List.of(), false, "sub");

        @Bean
        JwtDecoder userDecoder() {
            return decoder;
        }

        @Bean
        KeycloakJwtAuthenticationConverter userConverter() {
            return converter;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class UserMethodSecurityConfig {
    }
}
