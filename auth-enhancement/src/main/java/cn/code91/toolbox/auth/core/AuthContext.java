package cn.code91.toolbox.auth.core;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;
import java.util.Set;

/**
 * 当前用户静态门面（07 §4.1，R3 正门）。读 {@link SecurityContextHolder}，
 * {@code getPrincipal() instanceof Jwt} 时按需适配为 {@link CurrentUser}（无缓存：record
 * 构建成本低，不值得引入请求级缓存复杂度）。<b>依赖面钉死</b>：只触 spring-security-core 与
 * oauth2-jose 的 {@code Jwt}，不触 {@code JwtAuthenticationToken}（resource-server 类型）——
 * ArchUnit 第三条规则据此收窄（07 §4.1）。
 */
public final class AuthContext {

    private AuthContext() {
    }

    /** {@return 当前请求的已认证用户}；未认证/非 JWT 主体返回 empty（常态而非错误，07 §3 豁免） */
    public static Optional<CurrentUser> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        return Optional.of(CurrentUser.from(jwt, auth.getName()));
    }

    /** {@return 当前请求是否携带已认证的 JWT 主体} */
    public static boolean isAuthenticated() {
        return current().isPresent();
    }

    /** {@return 是否拥有指定 realm 角色}（原始名，不带 ROLE_ 前缀；null 入参恒 false——不可变集合 contains(null) 抛 NPE，Task 2 审查修正） */
    public static boolean hasRole(String realmRole) {
        if (realmRole == null) {
            return false;
        }
        return current().map(u -> u.realmRoles().contains(realmRole)).orElse(false);
    }

    /** {@return 是否拥有指定 client 的角色}（读 resource_access 原始结构，无跨 client 重名歧义；null 入参恒 false，同上） */
    public static boolean hasClientRole(String clientId, String role) {
        if (clientId == null || role == null) {
            return false;
        }
        return current()
                .map(u -> u.clientRoles().getOrDefault(clientId, Set.of()).contains(role))
                .orElse(false);
    }
}
