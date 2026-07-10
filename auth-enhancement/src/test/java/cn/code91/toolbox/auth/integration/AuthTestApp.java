package cn.code91.toolbox.auth.integration;

import cn.code91.facility.web.session.SessionUserHolder;
import cn.code91.toolbox.auth.core.AuthContext;
import cn.code91.toolbox.auth.core.CurrentUser;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 全链测试宿主（07 §9）：受保护端点回显 AuthContext 视图；@PreAuthorize 端点验证
 * method-security 默认开；/public 验证 permit-paths；/bridge 验证 SessionUserHolder 桥接。
 */
@SpringBootApplication
public class AuthTestApp {

    @RestController
    static class ProbeController {

        @GetMapping("/api/me")
        Map<String, Object> me() {
            CurrentUser user = AuthContext.current().orElseThrow();
            return Map.of(
                    "sub", user.sub(),
                    "username", user.username(),
                    "realmRoles", user.realmRoles(),
                    "hasOrderAdmin", AuthContext.hasRole("order-admin"),
                    "hasDocReader", AuthContext.hasClientRole("toolbox-api", "doc-reader"));
        }

        @GetMapping("/api/admin-only")
        @PreAuthorize("hasRole('order-admin')")
        Map<String, Object> adminOnly() {
            return Map.of("ok", true);
        }

        @GetMapping("/api/super-only")
        @PreAuthorize("hasRole('super-admin')")
        Map<String, Object> superOnly() {
            return Map.of("ok", true);
        }

        @GetMapping("/public/ping")
        Map<String, Object> ping() {
            return Map.of("pong", true);
        }

        @GetMapping("/api/bridge")
        Map<String, Object> bridge() {
            return Map.of("bridged", SessionUserHolder.getUser(CurrentUser.class).isPresent());
        }
    }
}
