package cn.code91.toolbox.llm.openai;

import cn.code91.facility.json.JsonUtil;
import cn.code91.facility.log.LogUtil;
import cn.code91.facility.ratelimit.RateLimitResult;
import cn.code91.facility.ratelimit.RateLimiterUtil;
import cn.code91.facility.result.Result;
import cn.code91.toolbox.llm.core.ChatOptions;
import cn.code91.toolbox.llm.core.ChatRequest;
import cn.code91.toolbox.llm.core.ChatResponse;
import cn.code91.toolbox.llm.core.LlmClient;
import cn.code91.toolbox.llm.core.LlmError;
import cn.code91.toolbox.llm.core.Message;
import cn.code91.toolbox.llm.core.Usage;
import cn.code91.toolbox.llm.spi.UsageListener;
import org.slf4j.MDC;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * OpenAI 兼容协议同步适配（brief 中唯一复杂类，03 §4.5）。{@link #chat} 编排固定顺序：
 * 客户端限流（裁定 F，命中直返不阻塞）→ 脱敏日志记录请求 → 发送请求（{@link #sendWithRetry}
 * 内完成裁定 C 的选择性指数退避重试）→ 成功则用量回调（失败不回调）；各环节独立小方法可单测
 * （同 mail {@code SmtpMailDispatcher} 拆法）。一切失败以 {@link LlmError} 错误值返回，
 * 公开 API 零异常外抛。
 *
 * <p><b>裁定 E 披露</b>：facility {@code HttpClients} 是单容器级共享 {@code RestClient} 的
 * 静态门面，其错误通道只收敛 HTTP 状态码（拿不到响应体做精细映射），也不支持"每模型独立
 * base-url/api-key/超时"。本类按裁定 E 的兜底条款直接持有专属 {@link RestClient} 实例
 * （baseUrl/超时按模型配置固化），不复用容器共享实例。</p>
 */
public final class OpenAiCompatibleClient implements LlmClient {

    /**
     * 限流门 Seam（包内可见，测试注入假门以演练限流分支而不依赖真实令牌桶状态）。
     * 默认实现委托 {@link RateLimiterUtil#acquire}。
     */
    @FunctionalInterface
    interface RateLimitGate {

        RateLimitResult acquire(String key, int permits, long capacity, double permitsPerSecond);
    }

    /**
     * 退避睡眠 Seam（包内可见，测试注入记录器/空实现以断言重试次数而不真实等待）。
     */
    @FunctionalInterface
    interface Sleeper {

        void sleep(Duration duration) throws InterruptedException;
    }

    private final OpenAiModelConfig config;
    private final List<UsageListener> usageListeners;
    private final RateLimitGate rateLimitGate;
    private final Sleeper sleeper;
    private final RestClient restClient;
    private final OpenAiErrorMapper errorMapper = new OpenAiErrorMapper();

    /**
     * 生产构造：限流门委托 facility {@link RateLimiterUtil}，退避真实睡眠。
     */
    public OpenAiCompatibleClient(OpenAiModelConfig config, List<UsageListener> usageListeners) {
        this(config, usageListeners, RateLimiterUtil::acquire, duration -> Thread.sleep(duration.toMillis()));
    }

    OpenAiCompatibleClient(OpenAiModelConfig config, List<UsageListener> usageListeners,
                           RateLimitGate rateLimitGate, Sleeper sleeper) {
        this.config = config;
        this.usageListeners = usageListeners == null ? List.of() : List.copyOf(usageListeners);
        this.rateLimitGate = rateLimitGate;
        this.sleeper = sleeper;
        this.restClient = buildRestClient(config);
    }

    /**
     * 每模型专属 {@link RestClient}：baseUrl 固化，连接/读超时取模型配置的 {@code timeout}
     * （裁定 E：facility {@code HttpClients} 无法承载"每模型独立超时"，故不复用其容器共享实例）。
     */
    private static RestClient buildRestClient(OpenAiModelConfig config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = (int) config.timeout().toMillis();
        factory.setConnectTimeout(timeoutMillis);
        factory.setReadTimeout(timeoutMillis);
        return RestClient.builder().baseUrl(config.baseUrl()).requestFactory(factory).build();
    }

    @Override
    public Result<ChatResponse, LlmError> chat(ChatRequest request) {
        LlmError rateLimited = checkRateLimit();
        if (rateLimited != null) {
            return Result.err(rateLimited);
        }

        logRequest(request);
        Instant start = Instant.now();
        Result<ChatResponse, LlmError> result = sendWithRetry(request);
        if (result.isOk()) {
            notifyUsageListeners(result.get().usage(), Duration.between(start, Instant.now()));
        }
        return result;
    }

    @Override
    public <T> Result<T, LlmError> chatStructured(ChatRequest request, Class<T> targetType) {
        Result<ChatResponse, LlmError> chatResult = chat(request);
        if (chatResult.isErr()) {
            return Result.err(chatResult.getErr());
        }
        return parseStructured(chatResult.get().content(), targetType);
    }

    /**
     * 结构化输出剥壳与鲁棒解析（裁定 B）：
     * <ol>
     *   <li>去除 markdown 围栏（{@code ```json ... ```} 或裸 {@code ``` ... ```}）；</li>
     *   <li>若剥壳后文本首尾仍非 JSON 结构符（{@code {}}/{@code []}），从原文中截取第一个
     *       {@code {}/[} 到最后一个匹配收尾符之间的子串（应对模型在 JSON 前后添加散文说明的
     *       常见情形，如"好的，以下是提取结果：{...}"）；</li>
     *   <li>经 facility {@code JsonUtil}（{@code CANONICAL} 命名空间，容忍未知字段更宽松）
     *       反序列化为目标类型；解析失败返回 {@code Err(SchemaMismatch)} 并携带<b>模型原始文本</b>
     *       （剥壳前，裁定 B"便于排查"要求）。</li>
     * </ol>
     */
    private <T> Result<T, LlmError> parseStructured(String rawText, Class<T> targetType) {
        String stripped = stripMarkdownFence(rawText);
        String candidate = extractJsonCandidate(stripped);
        Result<T, cn.code91.facility.error.WrappedError> parsed =
                JsonUtil.use(JsonUtil.CANONICAL).deserialize(candidate, targetType);
        if (parsed.isErr()) {
            return Result.err(new LlmError.SchemaMismatch(
                    "结构化输出解析失败：模型返回内容无法解析为 " + targetType.getSimpleName()
                            + "（" + parsed.getErr().getFormattedMessage() + "）", rawText));
        }
        return Result.ok(parsed.get());
    }

    private static final java.util.regex.Pattern FENCE_PATTERN =
            java.util.regex.Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * 去除 markdown 代码围栏：命中 {@code ```json ... ```} 或裸 {@code ``` ... ```} 时取围栏内文本；
     * 无围栏时原样返回（现实中模型也常常不加围栏直接输出 JSON）。
     */
    private static String stripMarkdownFence(String text) {
        java.util.regex.Matcher matcher = FENCE_PATTERN.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return text.trim();
    }

    /**
     * 从文本中截取首个 JSON 容器起止之间的子串，兜底"前后带散文"的情形（如模型输出
     * "以下是结果：{...}，希望有帮助"）。找不到容器边界符时原样返回，交由下游解析报错。
     */
    private static String extractJsonCandidate(String text) {
        int objStart = text.indexOf('{');
        int arrStart = text.indexOf('[');
        int start = firstNonNegative(objStart, arrStart);
        if (start < 0) {
            return text;
        }
        char open = text.charAt(start);
        char close = open == '{' ? '}' : ']';
        int end = text.lastIndexOf(close);
        if (end < start) {
            return text;
        }
        return text.substring(start, end + 1);
    }

    private static int firstNonNegative(int a, int b) {
        if (a < 0) {
            return b;
        }
        if (b < 0) {
            return a;
        }
        return Math.min(a, b);
    }

    /**
     * 裁定 F：{@code rate-limit-qps<=0} 完全不触碰限流门；命中时携带 {@code retryAfterMillis}
     * 建议等待直返，<b>不阻塞等待</b>。key 按模型名隔离。
     */
    private LlmError checkRateLimit() {
        if (config.rateLimitQps() <= 0) {
            return null;
        }
        long capacity = Math.max(1L, Math.round(config.rateLimitQps()));
        RateLimitResult acquired = rateLimitGate.acquire(config.modelName(), 1, capacity, config.rateLimitQps());
        if (acquired == null || acquired.allowed()) {
            return null;
        }
        return new LlmError.RateLimited(
                "模型 \"" + config.modelName() + "\" 命中客户端限流（rate-limit-qps=" + config.rateLimitQps()
                        + "），建议等待 " + acquired.retryAfterMillis() + "ms 后重试",
                Duration.ofMillis(acquired.retryAfterMillis()));
    }

    /**
     * 脱敏日志（全局约束 12）：请求消息内容经 {@code LogUtil}（写前自动 {@code MaskUtil} 脱敏，
     * 默认开）记录；<b>不记录 api-key</b>（Authorization 头值从不进日志）。
     */
    private void logRequest(ChatRequest request) {
        StringBuilder preview = new StringBuilder();
        for (Message message : request.messages()) {
            if (!preview.isEmpty()) {
                preview.append(" | ");
            }
            preview.append(message.role()).append(": ").append(message.content());
        }
        LogUtil.info("toolbox.llm 请求模型 \"{}\"：{}", config.modelName(), preview.toString());
    }

    /**
     * 重试语义（裁定 C）：仅 {@code RateLimited}（尊重服务端 {@code Retry-After}）/
     * {@code Timeout}/{@code NetworkError} 重试，同步阻塞式指数退避
     * （第 n 次重试前等待 {@code retryAfter 或 backoff * 2^(n-1)}）；其余四型直返；
     * 退避期间被中断则恢复中断位并放弃剩余重试（携带最近一次错误返回）。
     */
    private Result<ChatResponse, LlmError> sendWithRetry(ChatRequest request) {
        int maxAttempts = resolveMaxRetries(request.options()) + 1;
        int attempt = 0;
        while (true) {
            attempt++;
            Result<ChatResponse, LlmError> attemptResult = sendOnce(request);
            if (attemptResult.isOk()) {
                return attemptResult;
            }
            LlmError error = attemptResult.getErr();
            if (!isRetryable(error) || attempt >= maxAttempts) {
                return attemptResult;
            }
            Duration delay = backoffFor(error, attempt);
            LogUtil.warn("模型 \"{}\" 第 {}/{} 次调用遇可重试错误，{}ms 后重试：{}",
                    config.modelName(), attempt, maxAttempts, delay.toMillis(), error.message());
            try {
                sleeper.sleep(delay);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return attemptResult;
            }
        }
    }

    private static boolean isRetryable(LlmError error) {
        return error instanceof LlmError.RateLimited
                || error instanceof LlmError.Timeout
                || error instanceof LlmError.NetworkError;
    }

    /**
     * 退避时长：{@code RateLimited} 且服务端给出 {@code Retry-After} 时尊重该建议；
     * 否则用模型配置的退避基数指数倍增（{@code backoff * 2^(attempt-1)}）。
     */
    private Duration backoffFor(LlmError error, int attempt) {
        if (error instanceof LlmError.RateLimited rateLimited && rateLimited.retryAfter() != null) {
            return rateLimited.retryAfter();
        }
        return config.retryBackoff().multipliedBy(1L << (attempt - 1));
    }

    private int resolveMaxRetries(ChatOptions options) {
        return options.maxRetries() != null ? options.maxRetries() : config.maxRetries();
    }

    /**
     * 单次请求：构造协议 DTO → POST → 按结果分支映射为 {@link ChatResponse} 或 {@link LlmError}。
     * {@code RestClientResponseException}（4xx/5xx，有响应体可解析）优先于通用
     * {@code RestClientException} 捕获；后者内统一顺因果链判定超时/连接异常
     * （见下方 catch 分支注释：故障未必以 {@link ResourceAccessException} 现身）。
     */
    private Result<ChatResponse, LlmError> sendOnce(ChatRequest request) {
        OpenAiChatCompletionRequest body = toProtocolRequest(request);
        try {
            OpenAiChatCompletionResponse response = restClient.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + config.apiKey())
                    .body(body)
                    .retrieve()
                    .body(OpenAiChatCompletionResponse.class);
            return Result.ok(toChatResponse(response));
        } catch (RestClientResponseException httpError) {
            String retryAfterHeader = httpError.getResponseHeaders() == null
                    ? null : httpError.getResponseHeaders().getFirst("Retry-After");
            return Result.err(errorMapper.mapHttpError(
                    httpError.getStatusCode().value(), httpError.getResponseBodyAsString(), retryAfterHeader));
        } catch (RestClientException restError) {
            // 连接/超时故障并不总以 ResourceAccessException 现身：若故障发生在响应体读取阶段
            // （头部已到达、body 读取中被重置/超时），RestClient 会包成不携带状态码的
            // RestClientException（如 "Error while extracting response..."），真正的
            // SocketException/SocketTimeoutException 落在 cause 链底部（实测踩坑，WireMock
            // CONNECTION_RESET_BY_PEER 与固定延迟超时两种故障均如此）。故不区分具体子类，
            // 统一顺因果链查找已知网络异常类型；查无则兜底 ProviderError（未预期异常，
            // 全局约束 3：公开 API 零异常外抛）。
            Throwable timeoutCause = findCauseMatching(restError, errorMapper::isTimeoutException);
            if (timeoutCause != null) {
                return Result.err(errorMapper.mapTimeoutException(restError));
            }
            Throwable connectCause = findCauseMatching(restError, errorMapper::isConnectException);
            if (connectCause != null) {
                return Result.err(errorMapper.mapConnectException(restError));
            }
            return Result.err(new LlmError.ProviderError(
                    "大模型调用发生未分类异常：" + restError.getMessage(), null));
        } catch (Exception unexpected) {
            // 公开 API 零异常外抛（全局约束 3）：未预期异常（如消息转换器故障）兜底为
            // ProviderError，不让其外泄。
            return Result.err(new LlmError.ProviderError(
                    "大模型调用发生未分类异常：" + unexpected.getMessage(), null));
        }
    }

    /**
     * 有界遍历因果链，查找首个满足断言的异常（同 mail {@code SmtpErrorMapper} 的
     * 有界因果链遍历先例，防止被构造成环的因果链导致无限循环）。
     */
    private static final int MAX_CAUSE_DEPTH = 20;

    private static Throwable findCauseMatching(Throwable root, java.util.function.Predicate<Throwable> predicate) {
        Throwable current = root;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            if (predicate.test(current)) {
                return current;
            }
            current = current.getCause();
            depth++;
        }
        return null;
    }

    private OpenAiChatCompletionRequest toProtocolRequest(ChatRequest request) {
        List<OpenAiChatMessage> messages = request.messages().stream()
                .map(m -> new OpenAiChatMessage(roleOf(m.role()), m.content()))
                .toList();
        ChatOptions options = request.options();
        Double temperature = options.temperature() != null ? options.temperature() : config.temperature();
        Integer maxTokens = options.maxTokens() != null ? options.maxTokens() : config.maxTokens();
        return new OpenAiChatCompletionRequest(config.model(), messages, temperature, maxTokens, null);
    }

    private static String roleOf(cn.code91.toolbox.llm.core.Role role) {
        return role.name().toLowerCase(Locale.ROOT);
    }

    private static ChatResponse toChatResponse(OpenAiChatCompletionResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            return new ChatResponse("", Usage.zero(), null, response == null ? null : response.model());
        }
        OpenAiChatCompletionResponse.Choice first = response.choices().get(0);
        String content = first.message() == null ? "" : first.message().content();
        Usage usage = response.usage() == null ? Usage.zero()
                : new Usage(response.usage().promptTokens(), response.usage().completionTokens(),
                        response.usage().totalTokens());
        return new ChatResponse(content == null ? "" : content, usage, first.finishReason(), response.model());
    }

    /**
     * 全量回调（brief：全部 listener 都通知，仅在 chat 成功时）；单个回调异常吞掉并记日志，
     * 既不影响 chat 结果，也不影响后续 listener（同 mail {@code MailSendListener} 先例）。
     */
    private void notifyUsageListeners(Usage usage, Duration latency) {
        // MDC 键名 "traceId" 对齐 facility FacilityWebTraceProperties 的默认 mdc-key
        // （03 §4.4：TraceIdFilter 天然把这条链路串起来；facility 未提供独立的"读取当前
        // traceId"门面，其自身 TraceIdFilter 同样直接使用裸 MDC API，此处与其保持一致）。
        String traceId = MDC.get("traceId");
        for (UsageListener listener : usageListeners) {
            try {
                listener.onUsage(config.modelName(), usage, latency, traceId);
            } catch (Exception e) {
                LogUtil.warn("UsageListener {} 回调异常已忽略（不影响 chat 主流程）", e,
                        listener.getClass().getName());
            }
        }
    }
}
