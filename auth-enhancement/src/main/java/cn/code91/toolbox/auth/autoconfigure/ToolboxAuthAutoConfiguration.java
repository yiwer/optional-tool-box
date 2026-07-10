package cn.code91.toolbox.auth.autoconfigure;

import cn.code91.toolbox.auth.jwt.KeycloakJwtAuthenticationConverter;
import cn.code91.toolbox.auth.jwt.KeycloakJwtDecoderFactory;
import cn.code91.toolbox.auth.web.AuthAccessDeniedHandler;
import cn.code91.toolbox.auth.web.AuthEntryPoint;
import cn.code91.toolbox.auth.web.SessionUserBridgeFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * auth-enhancement 唯一装配入口（07 §5）。条件分层：L1 探测 oauth2-jose 在场 + Servlet 环境；
 * L2 总开关默认开；L4 Seam 三级——整链（主 Seam）/decoder/converter 任一用户 bean 即退让
 * （07 §5.3）。{@code before} 三个 Boot 官方装配构成退让链：本装配的 {@code JwtDecoder} 使
 * {@code OAuth2ResourceServerAutoConfiguration} 与 {@code UserDetailsServiceAutoConfiguration}
 * 退让，{@code SecurityFilterChain} 使默认 form-login 链退让。R5 fail-fast 由
 * {@code KeycloakJwtDecoderFactory}（经 {@code KeycloakEndpoints}）在 bean 构造期完成——
 * 缺 server-url/realm 阻断容器刷新，异常消息带最小配置样例。
 */
@AutoConfiguration(before = { OAuth2ResourceServerAutoConfiguration.class,
        SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class })
@ConditionalOnClass(name = "org.springframework.security.oauth2.jwt.JwtDecoder")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "toolbox.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ToolboxAuthProperties.class)
public class ToolboxAuthAutoConfiguration {

    /** Seam 2（07 §5.3）：换校验行为。构造期 R5 fail-fast；JWKS 懒加载（07 §5.4）。 */
    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder toolboxAuthJwtDecoder(ToolboxAuthProperties props) {
        var kc = props.keycloak();
        var jwt = props.jwt();
        return KeycloakJwtDecoderFactory.create(kc.serverUrl(), kc.realm(), kc.issuerUri(),
                kc.requiredAudience(), jwt.clockSkew(), jwt.jwsAlgorithms());
    }

    /** Seam 3（07 §5.3）：换映射行为（自类型条件）。 */
    @Bean
    @ConditionalOnMissingBean
    public KeycloakJwtAuthenticationConverter toolboxAuthJwtAuthenticationConverter(ToolboxAuthProperties props) {
        var jwt = props.jwt();
        return new KeycloakJwtAuthenticationConverter(
                jwt.mapRealmRoles(), jwt.clientRoles(), jwt.mapScopes(), jwt.principalClaim());
    }

    /**
     * 模块 i18n（00 §4.1 约定）：注册模块自有 MessageSource 参与 facility
     * {@code AggregatedMessageSource} 聚合（同 compare/storage 样板）；键前缀
     * {@code toolbox.auth.} 防聚合池污染。
     */
    @Bean("toolboxAuthMessageSource")
    @ConditionalOnMissingBean(name = "toolboxAuthMessageSource")
    public ResourceBundleMessageSource toolboxAuthMessageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("i18n/toolbox-auth-messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(false);
        messageSource.setFallbackToSystemLocale(false);
        return messageSource;
    }

    /**
     * 默认安全链（Seam 1，主 Seam；07 §5.3 默认链形态）：无状态、CSRF 关、CORS 委托
     * （有 CorsConfigurationSource bean 用之，否则回落 MVC 配置——facility CORS 自动生效）、
     * fail-closed（permit-paths 之外全鉴权，不内置默认放行）、401/403 双挂保证形态处处一致、
     * bridge 开启时挂 {@link SessionUserBridgeFilter}（认证后、授权前）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.web.SecurityFilterChain")
    @ConditionalOnProperty(prefix = "toolbox.auth.security-chain", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class SecurityChainConfiguration {

        @Bean
        @ConditionalOnMissingBean(SecurityFilterChain.class)
        SecurityFilterChain toolboxAuthSecurityFilterChain(HttpSecurity http, ToolboxAuthProperties props,
                                                           JwtDecoder decoder,
                                                           KeycloakJwtAuthenticationConverter converter)
                throws Exception {
            AuthEntryPoint entryPoint = new AuthEntryPoint();
            AuthAccessDeniedHandler deniedHandler = new AuthAccessDeniedHandler();
            http.csrf(AbstractHttpConfigurer::disable)
                    .cors(Customizer.withDefaults())
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(reg -> {
                        var permitPaths = props.securityChain().permitPaths();
                        if (!permitPaths.isEmpty()) {
                            reg.requestMatchers(permitPaths.toArray(String[]::new)).permitAll();
                        }
                        reg.anyRequest().authenticated();
                    })
                    .oauth2ResourceServer(rs -> rs
                            .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
                            .authenticationEntryPoint(entryPoint)
                            .accessDeniedHandler(deniedHandler))
                    .exceptionHandling(e -> e
                            .authenticationEntryPoint(entryPoint)
                            .accessDeniedHandler(deniedHandler));
            if (props.bridge().sessionUser().enabled()) {
                http.addFilterBefore(new SessionUserBridgeFilter(), AuthorizationFilter.class);
            }
            return http.build();
        }
    }

    /**
     * 方法级安全（07 §5.3）：默认开，@PreAuthorize 即用。消费方已自行 @EnableMethodSecurity
     * 时重复声明幂等（同名基础设施 bean 覆盖注册）；要完全自持则设
     * {@code toolbox.auth.method-security.enabled=false}。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "toolbox.auth.method-security", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
