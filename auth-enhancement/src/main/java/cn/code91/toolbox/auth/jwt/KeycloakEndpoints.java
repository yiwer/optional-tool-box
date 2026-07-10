package cn.code91.toolbox.auth.jwt;

import java.net.URI;

/**
 * Keycloak 端点本地派生（07 §4.2，纯函数）。issuer = {server-url 去尾斜杠}/realms/{realm}
 * （KC 17+ Quarkus 发行版布局；遗留 /auth 前缀由消费方并入 server-url）；jwks 为 issuer 下
 * 稳定公开契约路径。<b>不做任何网络请求</b>——R5：fail-fast 只对真配置错误（缺失/畸形），
 * KC 可达性问题留给运行期懒加载（07 §5.4）。
 */
public final class KeycloakEndpoints {

    private static final String CONFIG_GUIDANCE = """
            ；auth-enhancement 需要最小配置：
            toolbox:
              auth:
                keycloak:
                  server-url: https://kc.example.com
                  realm: myrealm
            （暂不启用请设 toolbox.auth.enabled=false，07 §5.4）""";

    private KeycloakEndpoints() {
    }

    /** {@return issuer 地址}（用于 iss 校验与 USAGE 示例） */
    public static String issuer(String serverUrl, String realm) {
        String base = validateServerUrl(serverUrl);
        if (realm == null || realm.isBlank()) {
            throw new IllegalStateException("缺少 toolbox.auth.keycloak.realm" + CONFIG_GUIDANCE);
        }
        return base + "/realms/" + realm.trim();
    }

    /** {@return JWKS 地址}（decoder 懒加载取签名公钥） */
    public static String jwksUri(String serverUrl, String realm) {
        return issuer(serverUrl, realm) + "/protocol/openid-connect/certs";
    }

    private static String validateServerUrl(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalStateException("缺少 toolbox.auth.keycloak.server-url" + CONFIG_GUIDANCE);
        }
        String trimmed = serverUrl.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "toolbox.auth.keycloak.server-url 不是合法 URL：\"" + trimmed + "\"" + CONFIG_GUIDANCE, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null) {
            throw new IllegalStateException(
                    "toolbox.auth.keycloak.server-url 必须是 http(s) 绝对地址：\"" + trimmed + "\"" + CONFIG_GUIDANCE);
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
