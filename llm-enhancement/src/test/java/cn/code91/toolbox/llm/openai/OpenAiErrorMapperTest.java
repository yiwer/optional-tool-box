package cn.code91.toolbox.llm.openai;

import cn.code91.toolbox.llm.core.LlmError;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OpenAiErrorMapper} 映射表穷尽（brief 交付物）：真实 HTTP 状态码 + 错误体断言，
 * 覆盖 401/403/429(+Retry-After)/400(上下文超长)/400(内容安全)/其余状态兜底 ProviderError、
 * 无法解析错误体、连接异常、超时异常的完整映射矩阵。
 */
class OpenAiErrorMapperTest {

    private final OpenAiErrorMapper mapper = new OpenAiErrorMapper();

    @Test
    void status401MapsToAuthError() {
        LlmError error = mapper.mapHttpError(401,
                "{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":\"invalid_request_error\"}}", null);

        assertThat(error).isInstanceOf(LlmError.AuthError.class);
        assertThat(error.message()).contains("Incorrect API key provided");
    }

    @Test
    void status403MapsToAuthError() {
        LlmError error = mapper.mapHttpError(403,
                "{\"error\":{\"message\":\"Forbidden: insufficient permission\",\"type\":\"permission_error\"}}", null);

        assertThat(error).isInstanceOf(LlmError.AuthError.class);
        assertThat(error.message()).contains("Forbidden");
    }

    @Test
    void status429WithRetryAfterHeaderMapsToRateLimitedRespectingHeader() {
        LlmError error = mapper.mapHttpError(429,
                "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_error\"}}", "20");

        assertThat(error).isInstanceOf(LlmError.RateLimited.class);
        assertThat(((LlmError.RateLimited) error).retryAfter()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void status429WithoutRetryAfterHeaderMapsToRateLimitedWithNullRetryAfter() {
        LlmError error = mapper.mapHttpError(429,
                "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"rate_limit_error\"}}", null);

        assertThat(error).isInstanceOf(LlmError.RateLimited.class);
        assertThat(((LlmError.RateLimited) error).retryAfter()).isNull();
    }

    @Test
    void status400WithContextLengthKeywordMapsToContextLengthExceeded() {
        LlmError error = mapper.mapHttpError(400,
                "{\"error\":{\"message\":\"This model's maximum context length is 4096 tokens\","
                        + "\"type\":\"invalid_request_error\",\"code\":\"context_length_exceeded\"}}", null);

        assertThat(error).isInstanceOf(LlmError.ContextLengthExceeded.class);
        assertThat(error.message()).contains("context length");
    }

    @Test
    void status400WithContentFilterKeywordMapsToContentFiltered() {
        LlmError error = mapper.mapHttpError(400,
                "{\"error\":{\"message\":\"Your request was rejected as a result of our safety system\","
                        + "\"type\":\"invalid_request_error\",\"code\":\"content_filter\"}}", null);

        assertThat(error).isInstanceOf(LlmError.ContentFiltered.class);
    }

    @Test
    void status400WithoutRecognizedKeywordFallsBackToProviderError() {
        LlmError error = mapper.mapHttpError(400,
                "{\"error\":{\"message\":\"Invalid value for temperature\",\"type\":\"invalid_request_error\","
                        + "\"code\":\"invalid_value\"}}", null);

        assertThat(error).isInstanceOf(LlmError.ProviderError.class);
        assertThat(((LlmError.ProviderError) error).providerCode()).isEqualTo("invalid_value");
    }

    @Test
    void otherHttpStatusFallsBackToProviderErrorWithCode() {
        LlmError error = mapper.mapHttpError(500,
                "{\"error\":{\"message\":\"Internal server error\",\"type\":\"server_error\",\"code\":\"internal_error\"}}", null);

        assertThat(error).isInstanceOf(LlmError.ProviderError.class);
        assertThat(((LlmError.ProviderError) error).providerCode()).isEqualTo("internal_error");
    }

    @Test
    void unparsableErrorBodyStillMapsUsingStatusCodeWithRawBodyAsMessage() {
        LlmError error = mapper.mapHttpError(500, "not a json body at all", null);

        assertThat(error).isInstanceOf(LlmError.ProviderError.class);
        assertThat(error.message()).contains("not a json body at all");
    }

    @Test
    void emptyErrorBodyStillMapsGracefully() {
        LlmError error = mapper.mapHttpError(500, "", null);

        assertThat(error).isInstanceOf(LlmError.ProviderError.class);
        assertThat(error.message()).isNotBlank();
    }

    @Test
    void connectExceptionMapsToNetworkError() {
        LlmError error = mapper.mapConnectException(new java.net.ConnectException("Connection refused"));

        assertThat(error).isInstanceOf(LlmError.NetworkError.class);
        assertThat(error.message()).contains("Connection refused");
    }

    @Test
    void unknownHostExceptionMapsToNetworkError() {
        LlmError error = mapper.mapConnectException(new java.net.UnknownHostException("bad.invalid"));

        assertThat(error).isInstanceOf(LlmError.NetworkError.class);
    }

    @Test
    void socketTimeoutExceptionMapsToTimeout() {
        LlmError error = mapper.mapTimeoutException(new java.net.SocketTimeoutException("Read timed out"));

        assertThat(error).isInstanceOf(LlmError.Timeout.class);
        assertThat(error.message()).contains("Read timed out");
    }
}
