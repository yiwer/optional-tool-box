package cn.code91.toolbox.auth.core;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 已认证的 Keycloak 用户视图（07 §4.1）。字段全部来自 access token claim；
 * {@code clientRoles} 读 {@code resource_access} 原始全量（不受 {@code toolbox.auth.jwt.client-roles}
 * 映射白名单限制——白名单只约束 GrantedAuthority 映射，见 07 §4.2 跨 client 重名披露）。
 *
 * @param sub        KC 用户 UUID（稳定主键）
 * @param username   principal 名（converter 已按 principal-claim 解析，缺失回落 sub）
 * @param email      email claim，可空
 * @param name       name claim，可空
 * @param realmRoles realm 角色原始名（未加 ROLE_ 前缀），不可变
 * @param clientRoles client-id → 角色集（resource_access 全量；角色集为空的 client 条目不出现在结果中），不可变
 * @param rawClaims  完整 claim 逃生舱，不可变
 */
public record CurrentUser(
        String sub,
        String username,
        String email,
        String name,
        Set<String> realmRoles,
        Map<String, Set<String>> clientRoles,
        Map<String, Object> rawClaims) {

    public CurrentUser {
        realmRoles = realmRoles == null ? Set.of() : Set.copyOf(realmRoles);
        clientRoles = clientRoles == null ? Map.of() : Map.copyOf(clientRoles);
        rawClaims = rawClaims == null ? Map.of() : Map.copyOf(rawClaims);
    }

    /**
     * 从 Jwt 适配（username 由调用方给定——AuthContext 用 authentication.getName()）。
     * null 值 claim 视同缺失剔除（Task 2 审查修正：Map.copyOf 拒绝 null 值，JSON null 经
     * 解码可合法出现在 claims 中；与 KeycloakClaims 的空安全语义一致）。
     */
    public static CurrentUser from(Jwt jwt, String username) {
        Map<String, Object> raw = new LinkedHashMap<>();
        jwt.getClaims().forEach((k, v) -> {
            if (v != null) {
                raw.put(k, v);
            }
        });
        return new CurrentUser(
                jwt.getSubject(),
                username == null || username.isBlank() ? jwt.getSubject() : username,
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                KeycloakClaims.realmRoles(jwt),
                KeycloakClaims.clientRoles(jwt),
                raw);
    }
}
