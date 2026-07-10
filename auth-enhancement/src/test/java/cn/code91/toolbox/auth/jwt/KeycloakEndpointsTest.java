package cn.code91.toolbox.auth.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Keycloak 端点本地派生（07 §4.2）：issuer/jwks 由 server-url + realm 纯函数拼出，
 * 启动零网络请求（R5）；对"真配置错误"抛带引导的 IllegalStateException（R5 fail-fast 源头）。
 */
@DisplayName("KeycloakEndpoints 端点派生")
class KeycloakEndpointsTest {

    @Test
    void derivesIssuerAndJwks() {
        assertThat(KeycloakEndpoints.issuer("https://kc.example.com", "myrealm"))
                .as("KC 17+ 布局：{server}/realms/{realm}")
                .isEqualTo("https://kc.example.com/realms/myrealm");
        assertThat(KeycloakEndpoints.jwksUri("https://kc.example.com", "myrealm"))
                .as("JWKS 为 issuer 下稳定路径")
                .isEqualTo("https://kc.example.com/realms/myrealm/protocol/openid-connect/certs");
    }

    @Test
    void trimsTrailingSlashAndKeepsLegacyPrefix() {
        assertThat(KeycloakEndpoints.issuer("https://kc.example.com/", "r"))
                .as("尾斜杠归一，避免双斜杠 issuer 不匹配")
                .isEqualTo("https://kc.example.com/realms/r");
        assertThat(KeycloakEndpoints.issuer("https://kc.example.com/auth", "r"))
                .as("遗留发行版 /auth 前缀并入 server-url 即可（07 §4.2）")
                .isEqualTo("https://kc.example.com/auth/realms/r");
    }

    @Test
    void failsFastWithGuidanceOnMissingOrMalformedConfig() {
        assertThatThrownBy(() -> KeycloakEndpoints.issuer(null, "r"))
                .as("缺 server-url：R5 启动期失败，消息带配置键与样例")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toolbox.auth.keycloak.server-url");
        assertThatThrownBy(() -> KeycloakEndpoints.issuer("https://kc.example.com", " "))
                .as("缺 realm")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toolbox.auth.keycloak.realm");
        assertThatThrownBy(() -> KeycloakEndpoints.issuer("not a url", "r"))
                .as("畸形 URL 属真配置错误")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server-url");
        assertThatThrownBy(() -> KeycloakEndpoints.issuer("ftp://kc.example.com", "r"))
                .as("非 http(s) scheme 拒绝")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http");
    }
}
