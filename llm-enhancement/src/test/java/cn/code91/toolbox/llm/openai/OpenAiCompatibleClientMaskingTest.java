package cn.code91.toolbox.llm.openai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.code91.facility.result.Result;
import cn.code91.toolbox.llm.core.ChatRequest;
import cn.code91.toolbox.llm.core.ChatResponse;
import cn.code91.toolbox.llm.core.LlmError;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 脱敏与 api-key 安全验证（brief 交付物）：含手机号/身份证的 prompt 落日志后必须已脱敏
 * （断言日志输出经 {@code MaskUtil}）；api-key 不出现在任何日志/错误消息。
 * logback-test.xml 把 {@code cn.code91.toolbox.llm} 全局调至 OFF 降噪，本测试临时抬回
 * INFO 并挂内存 appender 捕获事件断言，测毕复原（同 mail 沙箱 WARN 断言先例）。
 */
class OpenAiCompatibleClientMaskingTest {

    private WireMockServer wireMock;
    private Logger clientLogger;
    private Level originalLevel;
    private boolean originalAdditive;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void startWireMockAndCaptureLogs() {
        wireMock = new WireMockServer(0);
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());

        clientLogger = (Logger) LoggerFactory.getLogger(OpenAiCompatibleClient.class);
        originalLevel = clientLogger.getLevel();
        originalAdditive = clientLogger.isAdditive();
        appender = new ListAppender<>();
        appender.start();
        clientLogger.setLevel(Level.INFO);
        clientLogger.setAdditive(false);
        clientLogger.addAppender(appender);
    }

    @AfterEach
    void restoreLoggingAndStopWireMock() {
        clientLogger.detachAppender(appender);
        clientLogger.setAdditive(originalAdditive);
        clientLogger.setLevel(originalLevel);
        wireMock.stop();
    }

    private OpenAiCompatibleClient clientOf() {
        OpenAiModelConfig config = new OpenAiModelConfig("deepseek", "http://localhost:" + wireMock.port() + "/v1",
                "sk-super-secret-api-key-12345", "deepseek-chat", 0.2, 512, Duration.ofSeconds(5),
                0, Duration.ofMillis(10), 0);
        return new OpenAiCompatibleClient(config, List.of(), (key, permits, capacity, qps) -> null, duration -> { });
    }

    private static String successBody() {
        return "{\"model\":\"deepseek-chat\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"已收到\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}";
    }

    @Test
    void phoneNumberInPromptIsMaskedInLogOutput() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(successBody())));

        clientOf().chat(ChatRequest.user("我的手机号是13812345678，请联系我"));

        String allLogs = joinedLogMessages();
        assertThat(allLogs).as("手机号必须已脱敏").doesNotContain("13812345678");
        assertThat(allLogs).as("脱敏后应保留掩码格式").contains("138****5678");
    }

    @Test
    void idCardInPromptIsMaskedInLogOutput() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(successBody())));

        // mod11-2 校验通过的合法身份证号示例（按 GB 11643 算法计算末位校验码，MaskUtil 要求
        // 校验通过才遮蔽——不通过校验的 18 位数字串视为非真实证号，原样保留）。
        clientOf().chat(ChatRequest.user("身份证号：510381199001010012，请核实"));

        String allLogs = joinedLogMessages();
        assertThat(allLogs).as("身份证号必须已脱敏").doesNotContain("510381199001010012");
    }

    @Test
    void apiKeyNeverAppearsInLogOutputEvenOnFailure() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(500).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"boom\",\"type\":\"server_error\"}}")));

        clientOf().chat(ChatRequest.user("普通请求"));

        assertThat(joinedLogMessages()).doesNotContain("sk-super-secret-api-key-12345");
    }

    @Test
    void apiKeyNeverAppearsInSuccessfulResponseOrErrorValue() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(successBody())));

        Result<ChatResponse, LlmError> result = clientOf().chat(ChatRequest.user("普通请求"));

        assertThat(result.toString()).doesNotContain("sk-super-secret-api-key-12345");
    }

    private String joinedLogMessages() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            sb.append(event.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }
}
