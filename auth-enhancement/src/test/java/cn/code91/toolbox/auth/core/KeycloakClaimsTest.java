package cn.code91.toolbox.auth.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keycloak 私有 claim 结构解析（07 §4.2 表）：realm_access.roles / resource_access.{client}.roles
 * / scope。全程空安全——KC 可通过 mapper 配置剥除任一结构（07 §4.2"缺 claim 空安全"）。
 */
@DisplayName("KeycloakClaims 解析")
class KeycloakClaimsTest {

    /** 典型 KC access token claim 形态夹具（真实结构，07 §9 单测行） */
    private static Jwt kcJwt() {
        return baseJwt()
                .claim("realm_access", Map.of("roles", List.of("order-admin", "uma_authorization")))
                .claim("resource_access", Map.of(
                        "toolbox-api", Map.of("roles", List.of("doc-reader")),
                        "account", Map.of("roles", List.of("view-profile"))))
                .claim("scope", "openid profile email")
                .build();
    }

    private static Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("2f0c3f9a-0000-0000-0000-000000000001")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
    }

    @Test
    void parsesRealmClientRolesAndScopes() {
        Jwt jwt = kcJwt();
        assertThat(KeycloakClaims.realmRoles(jwt))
                .as("realm_access.roles 全量").containsExactlyInAnyOrder("order-admin", "uma_authorization");
        assertThat(KeycloakClaims.clientRoles(jwt))
                .as("resource_access 全 client（07 §4.1：CurrentUser 不受映射白名单限制）")
                .containsOnlyKeys("toolbox-api", "account");
        assertThat(KeycloakClaims.clientRoles(jwt).get("toolbox-api")).containsExactly("doc-reader");
        assertThat(KeycloakClaims.scopes(jwt))
                .as("scope 空格分隔").containsExactlyInAnyOrder("openid", "profile", "email");
    }

    @Test
    void missingClaimsYieldEmptyCollections() {
        Jwt bare = baseJwt().claim("dummy", "x").build();
        assertThat(KeycloakClaims.realmRoles(bare)).as("缺 realm_access 空安全").isEmpty();
        assertThat(KeycloakClaims.clientRoles(bare)).as("缺 resource_access 空安全").isEmpty();
        assertThat(KeycloakClaims.scopes(bare)).as("缺 scope 空安全").isEmpty();
    }

    @Test
    void malformedStructuresYieldEmptyCollections() {
        Jwt weird = baseJwt()
                .claim("realm_access", "not-a-map")
                .claim("resource_access", Map.of("c1", "not-a-map", "c2", Map.of("roles", "not-a-list")))
                .claim("scope", 42)
                .build();
        assertThat(KeycloakClaims.realmRoles(weird)).as("结构畸形不抛，按缺失处理").isEmpty();
        assertThat(KeycloakClaims.clientRoles(weird)).isEmpty();
        assertThat(KeycloakClaims.scopes(weird)).isEmpty();
    }
}
