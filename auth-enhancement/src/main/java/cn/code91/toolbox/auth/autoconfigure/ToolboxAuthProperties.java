package cn.code91.toolbox.auth.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * {@code toolbox.auth.*} 配置（07 §6 全字段）。
 *
 * @param enabled        模块总开关（实际装配由类上 {@code @ConditionalOnProperty(matchIfMissing=true)}
 *                       决定，本字段供配置元数据展示，同 llm 先例）
 * @param keycloak       Keycloak 连接面（必填项缺失 ⇒ 启动失败，R5）
 * @param jwt            claim 映射参数
 * @param securityChain  默认安全链参数
 * @param methodSecurity {@code @EnableMethodSecurity} 开关
 * @param bridge         facility SessionUserHolder 桥接（R3，默认关）
 */
@ConfigurationProperties(prefix = "toolbox.auth")
public record ToolboxAuthProperties(
        boolean enabled,
        @NestedConfigurationProperty Keycloak keycloak,
        @NestedConfigurationProperty Jwt jwt,
        @NestedConfigurationProperty SecurityChain securityChain,
        @NestedConfigurationProperty MethodSecurity methodSecurity,
        @NestedConfigurationProperty Bridge bridge) {

    public ToolboxAuthProperties {
        keycloak = keycloak == null ? new Keycloak(null, null, null, null) : keycloak;
        jwt = jwt == null ? Jwt.defaults() : jwt;
        securityChain = securityChain == null ? new SecurityChain(true, List.of()) : securityChain;
        methodSecurity = methodSecurity == null ? new MethodSecurity(true) : methodSecurity;
        bridge = bridge == null ? new Bridge(new Bridge.SessionUser(false)) : bridge;
    }

    /**
     * @param serverUrl        KC 基地址（必填；遗留 /auth 前缀发行版并入此值，07 §4.2）
     * @param realm            realm 名（必填）
     * @param issuerUri        可选覆写：内外网地址不一致时 issuer 校验用此值，JWKS 仍按 server-url 派生
     * @param requiredAudience 可选；设置才追加 audience 校验器（KC 默认 aud 不含 API client-id，
     *                         须 KC 侧配 mapper，07 §2.2 坑 2）
     */
    public record Keycloak(String serverUrl, String realm, String issuerUri, String requiredAudience) {
    }

    /**
     * @param principalClaim principal 名 claim（缺失回落 sub）
     * @param clockSkew      时间类校验容忍偏移
     * @param jwsAlgorithms  接受的签名算法（须与 KC realm 一致）
     * @param mapRealmRoles  realm_access.roles → ROLE_
     * @param clientRoles    要映射进 authorities 的 client-id 白名单（扁平 ROLE_，重名合并披露见 07 §4.2）
     * @param mapScopes      scope → SCOPE_
     */
    public record Jwt(
            @DefaultValue("preferred_username") String principalClaim,
            @DefaultValue("60s") Duration clockSkew,
            @DefaultValue("RS256") List<String> jwsAlgorithms,
            @DefaultValue("true") boolean mapRealmRoles,
            List<String> clientRoles,
            @DefaultValue("true") boolean mapScopes) {

        public Jwt {
            clientRoles = clientRoles == null ? List.of() : List.copyOf(clientRoles);
        }

        static Jwt defaults() {
            return new Jwt("preferred_username", Duration.ofSeconds(60), List.of("RS256"), true, List.of(), true);
        }
    }

    /**
     * @param enabled     关掉 ⇒ 只留 decoder/converter，链自己写（Seam 1 配置形态，07 §5.3）
     * @param permitPaths 显式放行路径（requestMatchers 模式）；空 = 全鉴权（fail-closed，07 §5.3）
     */
    public record SecurityChain(@DefaultValue("true") boolean enabled, List<String> permitPaths) {

        public SecurityChain {
            permitPaths = permitPaths == null ? List.of() : List.copyOf(permitPaths);
        }
    }

    /** @param enabled {@code @EnableMethodSecurity}（默认开，@PreAuthorize 即用） */
    public record MethodSecurity(@DefaultValue("true") boolean enabled) {
    }

    /** @param sessionUser facility SessionUserHolder 桥接 */
    public record Bridge(@NestedConfigurationProperty SessionUser sessionUser) {

        public Bridge {
            sessionUser = sessionUser == null ? new SessionUser(false) : sessionUser;
        }

        /** @param enabled 默认关（R3：副作用子开关默认关哲学） */
        public record SessionUser(@DefaultValue("false") boolean enabled) {
        }
    }
}
