package cn.code91.toolbox.auth.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.code91.toolbox.auth.jwt.KeycloakJwtDecoderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KC 不可达语义钉（07 §5.4）：服务照常启动（构建期零网络），bearer 请求得 401 而非 500——
 * IdP 故障不得放大为服务端错误。9 端口（discard）loopback 连接必被快速拒绝。
 * P2 R8 升级：401 description 细分为 jwks_unavailable（与坏 token 的 invalid_token 区分，
 * 07 §4.3）且取键失败分支打 WARN 单条（运维可见性）。
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
        // WARN 断言经内存 appender 捕获（llm OpenAiCompatibleClientLoggingTest 先例）；
        // setAdditive(false) 使预期 WARN 不落控制台，测试输出保持干净。
        Logger factoryLogger = (Logger) LoggerFactory.getLogger(KeycloakJwtDecoderFactory.class);
        boolean originalAdditive = factoryLogger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        factoryLogger.setAdditive(false);
        factoryLogger.addAppender(appender);
        try {
            String token = TestTokens.mint("http://127.0.0.1:9/realms/it", b -> { });
            mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.description").value("jwks_unavailable"));
            assertThat(appender.list)
                    .as("取键失败打 WARN 单条（R8 运维可见性），消息为网络内因摘要、无 token 成分")
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage()).contains("JWKS 取键失败");
                    });
        } finally {
            factoryLogger.detachAppender(appender);
            factoryLogger.setAdditive(originalAdditive);
        }
    }
}
