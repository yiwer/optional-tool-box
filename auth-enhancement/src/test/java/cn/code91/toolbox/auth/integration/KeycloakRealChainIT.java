package cn.code91.toolbox.auth.integration;

import cn.code91.facility.json.JsonUtil;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真 Keycloak 端到端（07 §9 Docker 行）：password grant 取真 token，验证真实 token 形态下的
 * 角色映射/principal/audience mapper——防"自签 token 与真 KC 漂移"。单例容器 +
 * disabledWithoutDocker（同 database PG 模式）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = AuthTestApp.class)
@AutoConfigureMockMvc
@DisplayName("真 Keycloak 端到端（IT）")
class KeycloakRealChainIT {

    static final KeycloakContainer KEYCLOAK = new KeycloakContainer()
            // 文件名须满足 {realm}-realm.json 约定（KC 26.5 目录导入校验，实测修正——
            // 早期/文档示例常见的裸 "{realm}.json" 命名在此版本会被拒绝并报 "File name /
            // realm name mismatch"，同批次踩坑同 wiremock-jetty12/spring-web 编译面豁免）。
            .withRealmImportFile("keycloak/it-realm-realm.json");

    static {
        KEYCLOAK.start();
    }

    @DynamicPropertySource
    static void authProps(DynamicPropertyRegistry registry) {
        registry.add("toolbox.auth.keycloak.server-url", KeycloakRealChainIT::serverUrl);
        registry.add("toolbox.auth.keycloak.realm", () -> "it-realm");
        registry.add("toolbox.auth.keycloak.required-audience", () -> "toolbox-api");
        registry.add("toolbox.auth.jwt.client-roles", () -> "toolbox-api");
    }

    private static String serverUrl() {
        String url = KEYCLOAK.getAuthServerUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void realTokenPassesFullChainWithMappedRoles() throws Exception {
        String token = passwordGrantToken();
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.hasOrderAdmin").value(true))
                .andExpect(jsonPath("$.hasDocReader").value(true));
        mockMvc.perform(get("/api/admin-only").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedStillRejected() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    /** password grant（login-client 开 directAccessGrants；audience mapper 补 aud=toolbox-api） */
    private static String passwordGrantToken() throws Exception {
        String form = "grant_type=password&client_id=login-client&username=alice&password=alice-pw";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl() + "/realms/it-realm/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        Map<?, ?> body = JsonUtil.deserialize(response.body(), Map.class)
                .get();
        return String.valueOf(body.get("access_token"));
    }
}
