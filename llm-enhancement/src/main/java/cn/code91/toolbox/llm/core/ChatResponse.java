package cn.code91.toolbox.llm.core;

import java.util.Objects;

/**
 * 对话响应（03 §4.1）。
 *
 * @param content      生成内容
 * @param usage        token 用量
 * @param finishReason 结束原因（如 {@code stop}/{@code length}/{@code content_filter}，原样透传服务端字段）
 * @param rawModel     服务端实际使用的模型标识（可能与请求配置的 {@code model} 不同，如厂商别名解析）
 */
public record ChatResponse(String content, Usage usage, String finishReason, String rawModel) {

    public ChatResponse {
        Objects.requireNonNull(content, "content 不得为 null");
        Objects.requireNonNull(usage, "usage 不得为 null");
    }
}
