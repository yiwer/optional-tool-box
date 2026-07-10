package cn.code91.toolbox.auth.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuthContext 静态门面（07 §4.1）：读 SecurityContextHolder，principal instanceof Jwt 才适配；
 * username 取 authentication.getName()（converter 已按 principal-claim 解析）。
 */
@DisplayName("AuthContext 门面")
class AuthContextTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Jwt kcJwt() {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .subject("sub-1")
                .claim("email", "alice@example.com")
                .claim("name", "Alice Doe")
                .claim("realm_access", Map.of("roles", List.of("order-admin")))
                .claim("resource_access", Map.of("toolbox-api", Map.of("roles", List.of("doc-reader"))))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    @Test
    void emptyWhenNoAuthentication() {
        assertThat(AuthContext.current()).as("无认证：空而非异常").isEmpty();
        assertThat(AuthContext.isAuthenticated()).isFalse();
        assertThat(AuthContext.hasRole("order-admin")).isFalse();
    }

    @Test
    void emptyWhenPrincipalIsNotJwt() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("plain-user", "n/a"));
        assertThat(AuthContext.current()).as("非 JWT 主体（如测试桩）不适配").isEmpty();
    }

    @Test
    void adaptsJwtPrincipalToCurrentUser() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken(kcJwt(), "n/a");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        CurrentUser user = AuthContext.current().orElseThrow();
        assertThat(user.sub()).isEqualTo("sub-1");
        assertThat(user.username()).as("桩 token 的 getName() 回落 toString，仅断言非空；" +
                "真实链路 converter 产 JwtAuthenticationToken(principalName)，username=alice 由全链测试钉").isNotBlank();
        assertThat(user.email()).isEqualTo("alice@example.com");
        assertThat(user.name()).isEqualTo("Alice Doe");
        assertThat(user.realmRoles()).containsExactly("order-admin");
        assertThat(user.clientRoles()).containsOnlyKeys("toolbox-api");
        assertThat(user.rawClaims()).as("完整 claim 逃生舱").containsKey("realm_access");

        assertThat(AuthContext.isAuthenticated()).isTrue();
        assertThat(AuthContext.hasRole("order-admin")).as("realm 角色原始名，不带 ROLE_ 前缀").isTrue();
        assertThat(AuthContext.hasRole("other")).isFalse();
        assertThat(AuthContext.hasClientRole("toolbox-api", "doc-reader")).isTrue();
        assertThat(AuthContext.hasClientRole("toolbox-api", "x")).isFalse();
        assertThat(AuthContext.hasClientRole("nope", "doc-reader")).isFalse();
    }
}
