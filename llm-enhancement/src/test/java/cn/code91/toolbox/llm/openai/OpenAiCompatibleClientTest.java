package cn.code91.toolbox.llm.openai;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.llm.core.ChatRequest;
import cn.code91.toolbox.llm.core.ChatResponse;
import cn.code91.toolbox.llm.core.LlmError;
import cn.code91.toolbox.llm.core.Usage;
import cn.code91.toolbox.llm.spi.UsageListener;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OpenAiCompatibleClient} 编排单测（brief 唯一复杂类，WireMock 模拟 OpenAI 兼容端点，
 * 无 Docker）：正常响应解析 / 401·403·429(+Retry-After)·400 两态 · 其余状态的错误映射与
 * 裁定 C 重试选择性（仅 RateLimited/Timeout/NetworkError 重试，其余直返）/ 客户端限流不阻塞
 * 直返（裁定 F）/ 用量回调 / api-key 走 Authorization 头而不落任何错误消息。
 */
class OpenAiCompatibleClientTest {

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

    private OpenAiModelConfig configOf(int maxRetries) {
        return new OpenAiModelConfig("deepseek", "http://localhost:" + wireMock.port() + "/v1",
                "sk-test-secret-key", "deepseek-chat", 0.2, 512, Duration.ofSeconds(5),
                maxRetries, Duration.ofMillis(10), 0, false);
    }

    /**
     * 限流测试专用配置：{@code rate-limit-qps>0}（configOf 默认关闭限流以免污染其余用例）。
     */
    private OpenAiModelConfig configWithRateLimit(double qps) {
        return new OpenAiModelConfig("deepseek", "http://localhost:" + wireMock.port() + "/v1",
                "sk-test-secret-key", "deepseek-chat", 0.2, 512, Duration.ofSeconds(5),
                2, Duration.ofMillis(10), qps, false);
    }

    private OpenAiCompatibleClient clientOf(int maxRetries) {
        return new OpenAiCompatibleClient(configOf(maxRetries), List.of(), (key, permits, capacity, qps) -> null,
                duration -> { });
    }

    @Test
    void successfulChatParsesContentUsageAndFinishReason() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"model":"deepseek-chat","choices":[{"message":{"role":"assistant","content":"你好，我能帮你什么？"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":8,"total_tokens":18}}
                        """)));

        Result<ChatResponse, LlmError> result = clientOf(2).chat(ChatRequest.user("你好"));

        assertThat(result.isOk()).isTrue();
        ChatResponse response = result.get();
        assertThat(response.content()).isEqualTo("你好，我能帮你什么？");
        assertThat(response.finishReason()).isEqualTo("stop");
        assertThat(response.rawModel()).isEqualTo("deepseek-chat");
        assertThat(response.usage()).isEqualTo(new Usage(10, 8, 18));
    }

    @Test
    void requestCarriesBearerAuthorizationHeaderWithApiKey() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(successBody())));

        clientOf(2).chat(ChatRequest.user("hi"));

        verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer sk-test-secret-key")));
    }

    @Test
    void status401MapsToAuthErrorAndIsNotRetried() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(401).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"Incorrect API key\",\"type\":\"invalid_request_error\"}}")));

        Result<ChatResponse, LlmError> result = clientOf(2).chat(ChatRequest.user("hi"));

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(LlmError.AuthError.class);
        verify(exactly(1), postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void status403MapsToAuthErrorAndIsNotRetried() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(403).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"Forbidden\",\"type\":\"permission_error\"}}")));

        Result<ChatResponse, LlmError> result = clientOf(2).chat(ChatRequest.user("hi"));

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(LlmError.AuthError.class);
        verify(exactly(1), postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void status429WithRetryAfterIsRetriedUpToMaxRetriesThenReturnsRateLimited() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(429).withHeader("Content-Type", "application/json")
                .withHeader("Retry-After", "0")
                .withBody("{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_error\"}}")));

        // maxRetries=2：首次 + 2 次重试 = 3 次尝试。
        Result<ChatResponse, LlmError> result = clientOf(2).chat(ChatRequest.user("hi"));

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(LlmError.RateLimited.class);
        verify(exactly(3), postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void status429EventuallySucceedsAfterTransientRetries() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).inScenario("flaky-429")
                .whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(429).withHeader("Content-Type", "application/json")
                        .withHeader("Retry-After", "0")
                        .withBody("{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_error\"}}"))
                .willSetStateTo("recovered"));
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).inScenario("flaky-429")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(successBody())));

        Result<ChatResponse, LlmError> result = clientOf(2).chat(ChatRequest.user("hi"));

        assertThat(result.isOk()).isTrue();
        verify(exactly(2), postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void connectionResetMapsToNetworkErrorAndIsRetried() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        Result<ChatResponse, LlmError> result = clientOf(2).chat(ChatRequest.user("hi"));

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(LlmError.NetworkError.class);
        // 首次 + 2 次重试 = 3 次尝试。
        verify(exactly(3), postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void timeoutMapsToTimeoutAndIsRetried() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(successBody())
                .withFixedDelay(3000)));

        OpenAiModelConfig fastTimeoutConfig = new OpenAiModelConfig("deepseek",
                "http://localhost:" + wireMock.port() + "/v1", "sk-test", "deepseek-chat",
                null, null, Duration.ofMillis(200), 1, Duration.ofMillis(10), 0, false);
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                fastTimeoutConfig, List.of(), (key, permits, capacity, qps) -> null, duration -> { });

        Result<ChatResponse, LlmError> result = client.chat(ChatRequest.user("hi"));

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(LlmError.Timeout.class);
        // 首次 + 1 次重试 = 2 次尝试。
        verify(exactly(2), postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void status400ContextLengthExceededIsNotRetried() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(400).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"This model's maximum context length is 4096 tokens\","
                        + "\"type\":\"invalid_request_error\",\"code\":\"context_length_exceeded\"}}")));

        Result<ChatResponse, LlmError> result = clientOf(2).chat(ChatRequest.user("hi"));

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(LlmError.ContextLengthExceeded.class);
        verify(exactly(1), postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void status400ContentFilteredIsNotRetried() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(400).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"Rejected by our safety system\","
                        + "\"type\":\"invalid_request_error\",\"code\":\"content_filter\"}}")));

        Result<ChatResponse, LlmError> result = clientOf(2).chat(ChatRequest.user("hi"));

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(LlmError.ContentFiltered.class);
        verify(exactly(1), postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void status500MapsToProviderErrorAndIsNotRetried() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(500).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"Internal error\",\"type\":\"server_error\"}}")));

        Result<ChatResponse, LlmError> result = clientOf(2).chat(ChatRequest.user("hi"));

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(LlmError.ProviderError.class);
        verify(exactly(1), postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void clientSideRateLimitReturnsRateLimitedWithoutCallingServerAndWithoutBlocking() {
        cn.code91.facility.ratelimit.RateLimitResult limited =
                new cn.code91.facility.ratelimit.RateLimitResult(false, 0, 1500);
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                configWithRateLimit(5), List.of(), (key, permits, capacity, qps) -> limited, duration -> {
                    throw new AssertionError("裁定 F：客户端限流命中不得阻塞等待");
                });

        long start = System.nanoTime();
        Result<ChatResponse, LlmError> result = client.chat(ChatRequest.user("hi"));
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(LlmError.RateLimited.class);
        assertThat(((LlmError.RateLimited) result.getErr()).retryAfter()).isEqualTo(Duration.ofMillis(1500));
        assertThat(elapsedMillis).as("限流命中必须立即返回，不阻塞等待").isLessThan(500);
        verify(exactly(0), postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    @Test
    void clientSideRateLimitKeyIsIsolatedByModelName() {
        AtomicInteger observedPermits = new AtomicInteger(-1);
        List<String> observedKeys = new ArrayList<>();
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(configWithRateLimit(5), List.of(),
                (key, permits, capacity, qps) -> {
                    observedKeys.add(key);
                    observedPermits.set(permits);
                    return null;
                }, duration -> { });
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(successBody())));

        client.chat(ChatRequest.user("hi"));

        assertThat(observedKeys).containsExactly("deepseek");
        assertThat(observedPermits.get()).isEqualTo(1);
    }

    @Test
    void rateLimitDisabledWhenQpsIsZero() {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(configOf(2), List.of(),
                (key, permits, capacity, qps) -> {
                    throw new AssertionError("rate-limit-qps=0 时不得触碰限流门");
                }, duration -> { });
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(successBody())));

        Result<ChatResponse, LlmError> result = client.chat(ChatRequest.user("hi"));

        assertThat(result.isOk()).isTrue();
    }

    @Test
    void usageListenerIsNotifiedOnSuccessfulChat() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(successBody())));
        List<Usage> captured = new ArrayList<>();
        UsageListener listener = (modelName, usage, latency, traceId) -> captured.add(usage);
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                configOf(2), List.of(listener), (key, permits, capacity, qps) -> null, duration -> { });

        client.chat(ChatRequest.user("hi"));

        assertThat(captured).containsExactly(new Usage(10, 8, 18));
    }

    @Test
    void allUsageListenersNotifiedAndThrowingListenerDoesNotAffectResult() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(successBody())));
        List<Usage> secondCaptured = new ArrayList<>();
        UsageListener throwing = (modelName, usage, latency, traceId) -> {
            throw new IllegalStateException("listener boom");
        };
        UsageListener second = (modelName, usage, latency, traceId) -> secondCaptured.add(usage);
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                configOf(2), List.of(throwing, second), (key, permits, capacity, qps) -> null, duration -> { });

        Result<ChatResponse, LlmError> result = client.chat(ChatRequest.user("hi"));

        assertThat(result.isOk()).as("回调异常不得影响 chat 结果").isTrue();
        assertThat(secondCaptured).hasSize(1);
    }

    @Test
    void usageListenerNotCalledOnFailedChat() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(401).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"bad key\",\"type\":\"invalid_request_error\"}}")));
        List<Usage> captured = new ArrayList<>();
        UsageListener listener = (modelName, usage, latency, traceId) -> captured.add(usage);
        OpenAiCompatibleClient client = new OpenAiCompatibleClient(
                configOf(2), List.of(listener), (key, permits, capacity, qps) -> null, duration -> { });

        client.chat(ChatRequest.user("hi"));

        assertThat(captured).isEmpty();
    }

    @Test
    void apiKeyNeverAppearsInAnyReturnedErrorMessage() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(500).withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"boom\",\"type\":\"server_error\"}}")));

        Result<ChatResponse, LlmError> result = clientOf(2).chat(ChatRequest.user("hi"));

        assertThat(result.getErr().message()).doesNotContain("sk-test-secret-key");
        assertThat(result.getErr().toString()).doesNotContain("sk-test-secret-key");
    }

    @Test
    void requestBodySerializesMessagesAndOptionsCorrectly() {
        wireMock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "application/json").withBody(successBody())));

        clientOf(2).chat(ChatRequest.builder().system("你是助手").user("你好").build());

        verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matching(".*\"model\"\\s*:\\s*\"deepseek-chat\".*"))
                .withRequestBody(matching(".*\"role\"\\s*:\\s*\"system\".*"))
                .withRequestBody(matching(".*\"role\"\\s*:\\s*\"user\".*")));
    }

    private static String successBody() {
        return """
                {"model":"deepseek-chat","choices":[{"message":{"role":"assistant","content":"ok"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":8,"total_tokens":18}}
                """;
    }
}
