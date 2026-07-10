package cn.code91.toolbox.auth.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keycloak 角色映射（07 §4.2 表）：realm_access → ROLE_、白名单 client → ROLE_（扁平）、
 * scope → SCOPE_；principal-claim 解析与 sub 回落。
 */
@DisplayName("KeycloakJwtAuthenticationConverter 映射")
class KeycloakJwtAuthenticationConverterTest {

    private static Jwt kcJwt() {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .subject("sub-1")
                .claim("preferred_username", "alice")
                .claim("realm_access", Map.of("roles", List.of("order-admin")))
                .claim("resource_access", Map.of(
                        "toolbox-api", Map.of("roles", List.of("doc-reader")),
                        "account", Map.of("roles", List.of("view-profile"))))
                .claim("scope", "openid profile")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private static List<String> authorityNames(AbstractAuthenticationToken token) {
        return token.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void defaultsMapRealmRolesAndScopesOnly() {
        var converter = new KeycloakJwtAuthenticationConverter(true, List.of(), true, "preferred_username");
        AbstractAuthenticationToken token = converter.convert(kcJwt());

        assertThat(authorityNames(token))
                .as("默认：realm 角色 + scope；client 角色白名单空不映射（07 §4.2）")
                .containsExactlyInAnyOrder("ROLE_order-admin", "SCOPE_openid", "SCOPE_profile");
        assertThat(token.getName()).as("principal 取 preferred_username").isEqualTo("alice");
    }

    @Test
    void whitelistedClientRolesAreFlattened() {
        var converter = new KeycloakJwtAuthenticationConverter(true, List.of("toolbox-api"), false, "preferred_username");
        assertThat(authorityNames(converter.convert(kcJwt())))
                .as("白名单 client 扁平 ROLE_；account 未列入不映射；map-scopes=false 无 SCOPE_")
                .containsExactlyInAnyOrder("ROLE_order-admin", "ROLE_doc-reader");
    }

    @Test
    void realmRolesCanBeDisabled() {
        var converter = new KeycloakJwtAuthenticationConverter(false, List.of(), true, "preferred_username");
        assertThat(authorityNames(converter.convert(kcJwt())))
                .containsExactlyInAnyOrder("SCOPE_openid", "SCOPE_profile");
    }

    @Test
    void principalFallsBackToSubWhenClaimMissing() {
        var converter = new KeycloakJwtAuthenticationConverter(true, List.of(), true, "preferred_username");
        Jwt noUsername = Jwt.withTokenValue("t").header("alg", "RS256").subject("sub-9")
                .claim("realm_access", Map.of("roles", List.of("r1")))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(converter.convert(noUsername).getName())
                .as("缺 principal-claim 回落 sub（07 §4.2）").isEqualTo("sub-9");
    }

    @Test
    void bareTokenYieldsNoAuthorities() {
        var converter = new KeycloakJwtAuthenticationConverter(true, List.of("toolbox-api"), true, "preferred_username");
        Jwt bare = Jwt.withTokenValue("t").header("alg", "RS256").subject("s")
                .claim("dummy", "x")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(converter.convert(bare).getAuthorities()).as("缺全部 claim 空安全").isEmpty();
    }
}
