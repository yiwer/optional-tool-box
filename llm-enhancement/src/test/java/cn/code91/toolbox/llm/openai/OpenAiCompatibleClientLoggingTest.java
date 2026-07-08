package cn.code91.toolbox.llm.openai;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.code91.facility.log.LogUtil;
import cn.code91.toolbox.llm.core.ChatRequest;
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
 * {@code toolbox.llm.log.*} 两个开关的行为验证（审查发现：此前声明却未接线）：
 * <ul>
 *   <li>{@code log.enabled} 作为总开关 gate 请求与响应内容日志；</li>
 *   <li>{@code log.mask-content} 控制本模块记录前是否显式 {@code MaskUtil.mask}——为把本模块这层
 *       与 {@code LogUtil} 全局脱敏隔离开以可观测断言，相关用例临时关闭 {@code LogUtil} 全局脱敏，
 *       测毕复原。</li>
 * </ul>
 * logback-test.xml 把 {@code cn.code91.toolbox.llm} 全局调至 OFF 降噪，本测试临时抬回 INFO 并挂
 * 内存 appender 捕获事件断言（同 {@link OpenAiCompatibleClientMaskingTest} 先例）。
 */
class OpenAiCompatibleClientLoggingTest {

    private WireMockServer wireMock;
    private Logger clientLogger;
    private Level originalLevel;
    private boolean originalAdditive;
    private boolean originalGlobalMasking;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(0);
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(successBody())));

        clientLogger = (Logger) LoggerFactory.getLogger(OpenAiCompatibleClient.class);
        originalLevel = clientLogger.getLevel();
        originalAdditive = clientLogger.isAdditive();
        originalGlobalMasking = LogUtil.isMaskingEnabled();
        appender = new ListAppender<>();
        appender.start();
        clientLogger.setLevel(Level.INFO);
        clientLogger.setAdditive(false);
        clientLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        clientLogger.detachAppender(appender);
        clientLogger.setAdditive(originalAdditive);
        clientLogger.setLevel(originalLevel);
        LogUtil.setMaskingEnabled(originalGlobalMasking);
        wireMock.stop();
    }

    /** 成功响应体，assistant 内容为 "已收到"。 */
    private static String successBody() {
        return "{\"model\":\"deepseek-chat\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"已收到\"},"
                + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2,\"total_tokens\":7}}";
    }

    private OpenAiCompatibleClient clientWith(OpenAiLogSettings logSettings) {
        OpenAiModelConfig config = new OpenAiModelConfig("deepseek", "http://localhost:" + wireMock.port() + "/v1",
                "sk-secret-key", "deepseek-chat", 0.2, 512, Duration.ofSeconds(5), 0, Duration.ofMillis(10), 0);
        return new OpenAiCompatibleClient(config, List.of(), logSettings,
                (key, permits, capacity, qps) -> null, duration -> { });
    }

    private String joinedLogs() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : appender.list) {
            sb.append(event.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }

    @Test
    void enabledFalseSuppressesBothRequestAndResponseContentLogs() {
        clientWith(new OpenAiLogSettings(false, true)).chat(ChatRequest.user("普通请求内容"));

        String logs = joinedLogs();
        assertThat(logs).as("log.enabled=false 时不应出现请求内容日志").doesNotContain("请求模型");
        assertThat(logs).as("log.enabled=false 时不应出现响应内容日志").doesNotContain("响应");
        assertThat(logs).as("请求内容不应落日志").doesNotContain("普通请求内容");
        assertThat(logs).as("响应内容不应落日志").doesNotContain("已收到");
    }

    @Test
    void enabledTrueLogsBothRequestAndResponsePreview() {
        clientWith(new OpenAiLogSettings(true, true)).chat(ChatRequest.user("你好模型"));

        String logs = joinedLogs();
        assertThat(logs).as("默认应记录请求预览").contains("请求模型").contains("你好模型");
        assertThat(logs).as("默认应记录响应预览").contains("响应").contains("已收到");
    }

    @Test
    void maskContentTrueMasksModuleLayerEvenWithGlobalMaskingOff() {
        // 关闭 LogUtil 全局脱敏，隔离出本模块这一层：mask-content=true 应仍脱敏（本模块自己调 MaskUtil）
        LogUtil.setMaskingEnabled(false);

        clientWith(new OpenAiLogSettings(true, true)).chat(ChatRequest.user("我的手机号是13812345678"));

        String logs = joinedLogs();
        assertThat(logs).as("mask-content=true 时本模块应已脱敏手机号").doesNotContain("13812345678");
        assertThat(logs).as("脱敏后应保留掩码格式").contains("138****5678");
    }

    @Test
    void maskContentFalseSkipsModuleLayerMaskingWhenGlobalMaskingOff() {
        // 关闭 LogUtil 全局脱敏后，mask-content=false 时本模块不预脱敏，手机号应原样出现
        // （证明本层开关真实生效；生产默认 LogUtil 全局脱敏开，故此路径可观察效果取决于全局设置）
        LogUtil.setMaskingEnabled(false);

        clientWith(new OpenAiLogSettings(true, false)).chat(ChatRequest.user("我的手机号是13812345678"));

        assertThat(joinedLogs()).as("mask-content=false 且全局脱敏关时手机号原样出现").contains("13812345678");
    }
}
