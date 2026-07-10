package cn.code91.toolbox.auth.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WireMock 假 JWKS 全链矩阵（07 §9）：200/401（过期/坏签/错 issuer/错 aud/无 token）/403
 * （缺角色）/permit-paths/桥接/方法级安全。WireMock 用静态单例（同 database PG 容器
 * 单例模式），@DynamicPropertySource 注入派生 server-url。
 */
@SpringBootTest(classes = AuthTestApp.class)
@AutoConfigureMockMvc
@DisplayName("JWKS 全链矩阵")
class JwksFullChainTest {

    static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIREMOCK.start();
        // WireMock 的 get/aResponse 与 MockMvcRequestBuilders.get 静态导入冲突，此处用类名限定
        WIREMOCK.stubFor(WireMock.get(WireMock.urlEqualTo("/realms/it/protocol/openid-connect/certs"))
                .willReturn(WireMock.aResponse().withHeader("Content-Type", "application/json")
                        .withBody(TestTokens.jwksJson())));
    }

    @DynamicPropertySource
    static void authProps(DynamicPropertyRegistry registry) {
        registry.add("toolbox.auth.keycloak.server-url", WIREMOCK::baseUrl);
        registry.add("toolbox.auth.keycloak.realm", () -> "it");
        registry.add("toolbox.auth.keycloak.required-audience", () -> "toolbox-api");
        registry.add("toolbox.auth.jwt.client-roles", () -> "toolbox-api");
        registry.add("toolbox.auth.security-chain.permit-paths", () -> "/public/**");
        registry.add("toolbox.auth.bridge.session-user.enabled", () -> "true");
    }

    @Autowired
    MockMvc mockMvc;

    private String issuer() {
        return WIREMOCK.baseUrl() + "/realms/it";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void validTokenReturns200WithMappedIdentity() throws Exception {
        mockMvc.perform(get("/api/me").header("Authorization", bearer(TestTokens.mint(issuer(), b -> { }))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.realmRoles[0]").value("order-admin"))
                .andExpect(jsonPath("$.hasDocReader").value(true));
    }

    @Test
    void missingTokenReturns401BaseResponseShape() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void expiredTokenReturns401InvalidToken() throws Exception {
        String expired = TestTokens.mint(issuer(), b -> b
                .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                .expirationTime(Date.from(Instant.now().minusSeconds(3600))));
        mockMvc.perform(get("/api/me").header("Authorization", bearer(expired)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.description").value("invalid_token"));
    }

    @Test
    void rogueSignatureReturns401() throws Exception {
        mockMvc.perform(get("/api/me").header("Authorization", bearer(TestTokens.mintRogue(issuer()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void wrongIssuerReturns401() throws Exception {
        String wrongIss = TestTokens.mint("https://evil.example/realms/it", b -> { });
        mockMvc.perform(get("/api/me").header("Authorization", bearer(wrongIss)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongAudienceReturns401() throws Exception {
        String wrongAud = TestTokens.mint(issuer(), b -> b.audience("other-api"));
        mockMvc.perform(get("/api/me").header("Authorization", bearer(wrongAud)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void preAuthorizeAllowsAndDenies() throws Exception {
        String token = TestTokens.mint(issuer(), b -> { });
        mockMvc.perform(get("/api/admin-only").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/super-only").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"insufficient_scope\""))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.description").value("insufficient_scope"));
    }

    @Test
    void permitPathsBypassAuthentication() throws Exception {
        mockMvc.perform(get("/public/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pong").value(true));
    }

    @Test
    void bridgePopulatesSessionUserHolderWithinRequest() throws Exception {
        mockMvc.perform(get("/api/bridge").header("Authorization", bearer(TestTokens.mint(issuer(), b -> { }))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bridged").value(true));
    }
}
