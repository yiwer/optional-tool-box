package cn.code91.toolbox.auth.core;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keycloak 私有 claim 结构的空安全导航（07 §4.2）。KC 把 realm 角色放在
 * {@code realm_access.roles}、client 角色放在 {@code resource_access.{client-id}.roles}
 * （皆非 OAuth2 标准 claim）；任一结构可被 KC 侧 mapper 配置剥除或改形，故全部按
 * "缺失/畸形 ⇒ 空集合"处理，绝不抛异常。返回集合均不可变。
 */
public final class KeycloakClaims {

    private KeycloakClaims() {
    }

    /** {@return realm_access.roles 原始角色名集合}（未加 ROLE_ 前缀） */
    public static Set<String> realmRoles(Jwt jwt) {
        return rolesOf(jwt.getClaim("realm_access"));
    }

    /** {@return resource_access 下全部 client → 角色集}（07 §4.1：读原始结构，不受映射白名单限制） */
    public static Map<String, Set<String>> clientRoles(Jwt jwt) {
        Object resourceAccess = jwt.getClaim("resource_access");
        if (!(resourceAccess instanceof Map<?, ?> byClient)) {
            return Map.of();
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        byClient.forEach((client, access) -> {
            Set<String> roles = rolesOf(access);
            if (client instanceof String clientId && !roles.isEmpty()) {
                result.put(clientId, roles);
            }
        });
        return Map.copyOf(result);
    }

    /** {@return scope claim 按空格拆分的集合}（标准 OAuth2 语义，Spring SCOPE_ 映射的数据源） */
    public static Set<String> scopes(Jwt jwt) {
        Object scope = jwt.getClaim("scope");
        if (!(scope instanceof String s) || s.isBlank()) {
            return Set.of();
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String item : s.trim().split("\\s+")) {
            scopes.add(item);
        }
        return Set.copyOf(scopes);
    }

    private static Set<String> rolesOf(Object accessEntry) {
        if (!(accessEntry instanceof Map<?, ?> access) || !(access.get("roles") instanceof Collection<?> roles)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object role : roles) {
            if (role instanceof String r && !r.isBlank()) {
                result.add(r);
            }
        }
        return Set.copyOf(result);
    }
}
