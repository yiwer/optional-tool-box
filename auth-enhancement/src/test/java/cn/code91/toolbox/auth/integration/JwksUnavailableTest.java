package cn.code91.toolbox.auth.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KC 不可达语义钉（07 §5.4）：服务照常启动（构建期零网络），bearer 请求得 401 而非 500——
 * IdP 故障不得放大为服务端错误。9 端口（discard）loopback 连接必被快速拒绝。
 */
@SpringBootTest(classes = AuthTestApp.class, properties = {
        "toolbox.auth.keycloak.server-url=http://127.0.0.1:9",
        "toolbox.auth.keycloak.realm=it" })
@AutoConfigureMockMvc
@DisplayName("JWKS 不可达语义")
class JwksUnavailableTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void unreachableJwksYields401Not500() throws Exception {
        String token = TestTokens.mint("http://127.0.0.1:9/realms/it", b -> { });
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }
}
