package cn.code91.toolbox.llm.openai;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.llm.core.ChatRequest;
import cn.code91.toolbox.llm.core.LlmError;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.notMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code chatStructured} 剥壳鲁棒性矩阵（brief 交付物，裁定 B）：干净 JSON / markdown 围栏包裹 /
 * 前后带散文 / 非法 JSON 四态。
 */
class OpenAiCompatibleClientStructuredOutputTest {

    record TicketInfo(String category, String urgency) {
    }

    private WireMockServer wireMock;

    @BeforeEach
    void startWireMock() {
        wireMock = new WireMockServer(0);
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
    }

    @AfterEach
    void stopWireMock() {
        wireMock.stop();
    }

    private OpenAiCompatibleClient clientOf() {
        return clientOf(false);
    }

    private OpenAiCompatibleClient clientOf(boolean jsonMode) {
        OpenAiModelConfig config = new OpenAiModelConfig("deepseek", "http://localhost:" + wireMock.port() + "/v1",
                "sk-test", "deepseek-chat", 0.2, 512, Duration.ofSeconds(5), 0, Duration.ofMillis(10), 0, jsonMode);
        return new OpenAiCompatibleClient(config, List.of(), (key, permits, capacity, qps) -> null, duration -> { });
    }

    private void stubModelReply(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"model\":\"deepseek-chat\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\""
                        + escaped + "\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":5,\"total_tokens\":10}}")));
    }

    @Test
    void cleanJsonParsesDirectly() {
        stubModelReply("{\"category\":\"billing\",\"urgency\":\"high\"}");

        Result<TicketInfo, LlmError> result = clientOf().chatStructured(ChatRequest.user("x"), TicketInfo.class);

        assertThat(result.isOk()).isTrue();
        assertThat(result.get()).isEqualTo(new TicketInfo("billing", "high"));
    }

    @Test
    void markdownFencedJsonWithLanguageTagIsStrippedThenParsed() {
        stubModelReply("```json\n{\"category\":\"billing\",\"urgency\":\"high\"}\n```");

        Result<TicketInfo, LlmError> result = clientOf().chatStructured(ChatRequest.user("x"), TicketInfo.class);

        assertThat(result.isOk()).isTrue();
        assertThat(result.get()).isEqualTo(new TicketInfo("billing", "high"));
    }

    @Test
    void bareMarkdownFenceWithoutLanguageTagIsStrippedThenParsed() {
        stubModelReply("```\n{\"category\":\"billing\",\"urgency\":\"high\"}\n```");

        Result<TicketInfo, LlmError> result = clientOf().chatStructured(ChatRequest.user("x"), TicketInfo.class);

        assertThat(result.isOk()).isTrue();
        assertThat(result.get()).isEqualTo(new TicketInfo("billing", "high"));
    }

    @Test
    void jsonSurroundedByProseIsExtractedThenParsed() {
        stubModelReply("好的，以下是提取结果：{\"category\":\"billing\",\"urgency\":\"high\"}，希望有帮助！");

        Result<TicketInfo, LlmError> result = clientOf().chatStructured(ChatRequest.user("x"), TicketInfo.class);

        assertThat(result.isOk()).isTrue();
        assertThat(result.get()).isEqualTo(new TicketInfo("billing", "high"));
    }

    @Test
    void invalidJsonReturnsSchemaMismatchWithRawModelText() {
        String garbage = "抱歉，我无法处理该请求。";
        stubModelReply(garbage);

        Result<TicketInfo, LlmError> result = clientOf().chatStructured(ChatRequest.user("x"), TicketInfo.class);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(LlmError.SchemaMismatch.class);
        LlmError.SchemaMismatch mismatch = (LlmError.SchemaMismatch) result.getErr();
        assertThat(mismatch.rawText()).isEqualTo(garbage);
    }

    @Test
    void malformedJsonInsideFenceReturnsSchemaMismatchWithOriginalFencedText() {
        String malformed = "```json\n{\"category\": \"billing\", \"urgency\": \n```";
        stubModelReply(malformed);

        Result<TicketInfo, LlmError> result = clientOf().chatStructured(ChatRequest.user("x"), TicketInfo.class);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(LlmError.SchemaMismatch.class);
        // 携带的是模型原始文本（剥壳前），而非剥壳/截取后的候选串，裁定 B 明确"便于排查"要求。
        assertThat(((LlmError.SchemaMismatch) result.getErr()).rawText()).isEqualTo(malformed);
    }

    @Test
    void chatFailureShortCircuitsBeforeStructuredParsing() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(401).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"bad key\",\"type\":\"invalid_request_error\"}}")));

        Result<TicketInfo, LlmError> result = clientOf().chatStructured(ChatRequest.user("x"), TicketInfo.class);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(LlmError.AuthError.class);
    }

    @Test
    void jsonModeOnAttachesResponseFormatOnStructuredCalls() {
        stubModelReply("{\"category\":\"billing\",\"urgency\":\"high\"}");

        clientOf(true).chatStructured(ChatRequest.user("x"), TicketInfo.class);

        verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matching(".*\"response_format\"\\s*:\\s*\\{\\s*\"type\"\\s*:\\s*\"json_object\"\\s*\\}.*")));
    }

    @Test
    void jsonModeOffOmitsResponseFormat() {
        stubModelReply("{\"category\":\"billing\",\"urgency\":\"high\"}");

        clientOf(false).chatStructured(ChatRequest.user("x"), TicketInfo.class);

        verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(notMatching(".*response_format.*")));
    }

    @Test
    void jsonModeOnDoesNotAffectPlainChat() {
        // json-mode 是 chatStructured 专属提示：普通 chat 即便模型开了 json-mode 也不附带
        // （否则普通对话被强制 JSON 输出）。
        stubModelReply("普通回答");

        clientOf(true).chat(ChatRequest.user("x"));

        verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(notMatching(".*response_format.*")));
    }
}
