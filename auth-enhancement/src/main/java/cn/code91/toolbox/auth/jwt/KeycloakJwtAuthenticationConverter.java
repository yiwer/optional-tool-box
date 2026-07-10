package cn.code91.toolbox.auth.jwt;

import cn.code91.toolbox.auth.core.KeycloakClaims;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keycloak 私有角色结构 → Spring authorities（07 §4.2）。三路并集：
 * realm_access.roles → {@code ROLE_{role}}（开关 mapRealmRoles）；resource_access 白名单
 * client → {@code ROLE_{role}} 扁平化（跨 client 重名合并——坦诚披露，需区分时业务改用
 * {@code AuthContext.hasClientRole}）；scope → {@code SCOPE_{s}}（保留 Spring 原生语义）。
 * 角色名原始大小写，不转换。principal 名取 principalClaim，缺失回落 sub。
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final boolean mapRealmRoles;
    private final List<String> clientRoleClients;
    private final boolean mapScopes;
    private final String principalClaim;

    public KeycloakJwtAuthenticationConverter(boolean mapRealmRoles, List<String> clientRoleClients,
                                              boolean mapScopes, String principalClaim) {
        this.mapRealmRoles = mapRealmRoles;
        this.clientRoleClients = clientRoleClients == null ? List.of() : List.copyOf(clientRoleClients);
        this.mapScopes = mapScopes;
        this.principalClaim = principalClaim == null || principalClaim.isBlank()
                ? "preferred_username" : principalClaim;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        if (mapRealmRoles) {
            KeycloakClaims.realmRoles(jwt).forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        }
        var clientRoles = KeycloakClaims.clientRoles(jwt);
        for (String client : clientRoleClients) {
            clientRoles.getOrDefault(client, Set.of())
                    .forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        }
        if (mapScopes) {
            KeycloakClaims.scopes(jwt).forEach(s -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + s)));
        }
        String principal = jwt.getClaimAsString(principalClaim);
        if (principal == null || principal.isBlank()) {
            principal = jwt.getSubject();
        }
        return new JwtAuthenticationToken(jwt, authorities, principal);
    }
}
