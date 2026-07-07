package cn.code91.toolbox.llm.openai;

import cn.code91.facility.json.JsonUtil;
import cn.code91.toolbox.llm.core.LlmError;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;

/**
 * OpenAI 兼容协议 HTTP 状态/错误体 → {@link LlmError} 的收敛点（brief 交付物，03 §4.5，
 * 单测穷尽映射表）。
 *
 * <p><b>映射表</b>：401/403 → {@code AuthError}；429 → {@code RateLimited}（响应含
 * {@code Retry-After} 头则尊重，裁定 F/03 §4.2）；400 且错误体 {@code message}/{@code code}
 * 含上下文长度关键字 → {@code ContextLengthExceeded}；400 且含内容安全关键字 →
 * {@code ContentFiltered}；其余状态（含 400 未命中前两类关键字）→ {@code ProviderError}
 * 携带解析出的 {@code code} 字段。</p>
 *
 * <p><b>关键字判定顺序不可调换</b>：先判上下文超长，再判内容安全——二者均为 400 状态，
 * 顺序仅影响两个关键字集合同时命中的边界情形（现实中不会同时出现，顺序仅为确定性保证）。</p>
 */
public final class OpenAiErrorMapper {

    /**
     * 上下文超长关键字（错误体 message/code 命中任一即判定，各厂商措辞不同但均含这些词根）。
     */
    private static final String[] CONTEXT_LENGTH_KEYWORDS = {
            "context_length_exceeded", "context length", "maximum context length", "too many tokens"
    };

    /**
     * 内容安全拦截关键字。
     */
    private static final String[] CONTENT_FILTER_KEYWORDS = {
            "content_filter", "safety system", "content policy", "content management policy"
    };

    /**
     * 将 HTTP 错误响应映射为 {@link LlmError}。
     *
     * @param statusCode   HTTP 状态码
     * @param responseBody 原始响应体（可能不是合法 JSON，如网关返回的 HTML 错误页）
     * @param retryAfter   {@code Retry-After} 响应头原始值（秒数字符串），无该头为 null
     */
    public LlmError mapHttpError(int statusCode, String responseBody, String retryAfter) {
        OpenAiErrorBody.Detail detail = parseDetail(responseBody);
        String message = detail != null && detail.message() != null && !detail.message().isBlank()
                ? detail.message()
                : fallbackMessage(statusCode, responseBody);

        if (statusCode == 401 || statusCode == 403) {
            return new LlmError.AuthError("大模型鉴权失败（HTTP " + statusCode + "）：" + message);
        }
        if (statusCode == 429) {
            return new LlmError.RateLimited(
                    "大模型限流（HTTP 429）：" + message, parseRetryAfter(retryAfter));
        }
        if (statusCode == 400) {
            String haystack = (message + " " + codeOf(detail)).toLowerCase(java.util.Locale.ROOT);
            if (containsAny(haystack, CONTEXT_LENGTH_KEYWORDS)) {
                return new LlmError.ContextLengthExceeded("提示词超出模型上下文窗口：" + message);
            }
            if (containsAny(haystack, CONTENT_FILTER_KEYWORDS)) {
                return new LlmError.ContentFiltered("内容安全策略拦截：" + message);
            }
        }
        return new LlmError.ProviderError(
                "大模型调用失败（HTTP " + statusCode + "）：" + message, codeOf(detail));
    }

    /**
     * 连接/IO 层异常映射（裁定 C 可重试类型）：DNS 解析失败、连接拒绝、路由不可达等。
     */
    public LlmError mapConnectException(Exception exception) {
        return new LlmError.NetworkError("大模型请求网络异常：" + rootMessage(exception));
    }

    /**
     * 超时异常映射（裁定 C 可重试类型）。
     */
    public LlmError mapTimeoutException(Exception exception) {
        return new LlmError.Timeout("大模型请求超时：" + rootMessage(exception));
    }

    /**
     * 判定异常是否为连接层异常（供 adapter 决定走 {@link #mapConnectException} 还是
     * {@link #mapTimeoutException}——{@link SocketTimeoutException} 优先判为超时，
     * 因其同时是 {@link java.io.IOException} 但语义更贴近"超时"而非"连接失败"）。
     */
    public boolean isTimeoutException(Throwable throwable) {
        return throwable instanceof SocketTimeoutException;
    }

    public boolean isConnectException(Throwable throwable) {
        return throwable instanceof ConnectException
                || throwable instanceof UnknownHostException
                || throwable instanceof NoRouteToHostException
                || throwable instanceof SocketException;
    }

    private static OpenAiErrorBody.Detail parseDetail(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }
        return JsonUtil.deserialize(responseBody, OpenAiErrorBody.class)
                .toOptional()
                .map(OpenAiErrorBody::error)
                .orElse(null);
    }

    private static String codeOf(OpenAiErrorBody.Detail detail) {
        if (detail == null) {
            return null;
        }
        if (detail.code() != null && !detail.code().isBlank()) {
            return detail.code();
        }
        return detail.type();
    }

    private static String fallbackMessage(int statusCode, String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "服务端未返回错误详情（HTTP " + statusCode + "）";
        }
        return responseBody;
    }

    /**
     * {@code Retry-After} 头解析（裁定 F）：仅支持秒数形式（OpenAI 兼容端点的通行形态），
     * HTTP-date 形式极罕见故不特殊处理；解析失败（非法数字）视为未提供，退回 adapter 自身退避基数。
     */
    private static Duration parseRetryAfter(String retryAfter) {
        if (retryAfter == null || retryAfter.isBlank()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(retryAfter.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean containsAny(String haystack, String[] keywords) {
        for (String keyword : keywords) {
            if (haystack.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }
}
