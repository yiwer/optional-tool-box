package cn.code91.toolbox.llm.core;

import java.time.Duration;

/**
 * 大模型调用错误类型，穷尽 8 种子类型（裁定 A/03 §4.2），调用方可 pattern matching 完全处理。
 * HTTP/解析异常一律在 openai adapter 内收敛为本类型，不得从公开 API 外抛（全局约束 3）。
 *
 * <p>重试语义（裁定 C，由 adapter 实现，本类型本身不含重试逻辑）：仅 {@link RateLimited}
 * （尊重 {@code retryAfter}）/{@link Timeout}/{@link NetworkError} 重试，指数退避；其余五型
 * 一律直返不重试——{@link AuthError} 与 {@link ContentFiltered}/{@link ContextLengthExceeded}/
 * {@link SchemaMismatch} 重试无意义（请求形态或凭证不会因重试而改变），{@link ProviderError}
 * 未归类故不假定可重试。</p>
 */
public sealed interface LlmError {

    /**
     * 错误描述信息（面向开发者的中文描述，P1 不做 i18n，同 storage/mail/database 先例）。
     */
    String message();

    /**
     * 密钥无效（HTTP 401/403），重试无意义，直返不重试。
     */
    record AuthError(String message) implements LlmError {
    }

    /**
     * 触发限流（HTTP 429）。可重试（裁定 C），若响应含 {@code Retry-After} 则尊重该建议等待。
     *
     * @param retryAfter 服务端建议的等待时长；响应未给出时置 null（adapter 退回自身指数退避基数）
     */
    record RateLimited(String message, Duration retryAfter) implements LlmError {
    }

    /**
     * 提示词超出模型上下文窗口（HTTP 400 且错误体含上下文长度相关关键字）。重试无意义
     * （请求内容不会因重试变短），直返不重试。
     */
    record ContextLengthExceeded(String message) implements LlmError {
    }

    /**
     * 内容安全策略拦截。重试无意义且徒增计费调用次数（裁定 C 原文强调"烧钱"），直返不重试。
     */
    record ContentFiltered(String message) implements LlmError {
    }

    /**
     * 结构化输出解析或 schema 校验失败（裁定 B）。重试无意义，直返不重试。
     *
     * @param rawText 模型原始返回文本（剥壳前），便于排查模型实际输出了什么
     */
    record SchemaMismatch(String message, String rawText) implements LlmError {
    }

    /**
     * 请求超时。可重试（裁定 C），指数退避。
     */
    record Timeout(String message) implements LlmError {
    }

    /**
     * 网络/连接层错误（DNS、连接拒绝、IO 异常等）。可重试（裁定 C），指数退避。
     */
    record NetworkError(String message) implements LlmError {
    }

    /**
     * 未归入其余类型的厂商错误兜底。是否可重试未知，裁定 C 明确将其归入"不重试"一侧
     * （避免对未知错误形态盲目重试）。
     *
     * @param providerCode 厂商错误码/错误类型字段原样保留（不同兼容端点字段名不同，
     *                     取值为 adapter 从错误体解析出的最贴近"错误码"的字符串，
     *                     解析不出时为 null）
     */
    record ProviderError(String message, String providerCode) implements LlmError {
    }
}
