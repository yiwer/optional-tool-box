package cn.code91.toolbox.llm.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 对话请求（03 §4.1），经 {@link #builder()} 构造；{@link #user(String)} 为最常见的
 * "单条用户消息、无自定义选项"场景提供便捷构造。
 */
public record ChatRequest(List<Message> messages, ChatOptions options) {

    public ChatRequest {
        Objects.requireNonNull(messages, "messages 不得为 null");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages 不得为空：至少需一条消息");
        }
        messages = List.copyOf(messages);
        options = options == null ? ChatOptions.defaults() : options;
    }

    /**
     * 便捷构造：单条用户消息、默认选项（03 §8 使用示例的典型入口）。
     */
    public static ChatRequest user(String content) {
        return new ChatRequest(List.of(Message.user(content)), ChatOptions.defaults());
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * builder 语义（同 mail {@code MailMessage.Builder} 先例）：{@code message}/{@code system}/
     * {@code user}/{@code assistant} 多次调用为<b>累加</b>；{@code options} 后设者覆盖先设者。
     */
    public static final class Builder {

        private final List<Message> messages = new ArrayList<>();
        private ChatOptions options = ChatOptions.defaults();

        private Builder() {
        }

        public Builder message(Message message) {
            this.messages.add(Objects.requireNonNull(message, "message 不得为 null"));
            return this;
        }

        public Builder system(String content) {
            return message(Message.system(content));
        }

        public Builder user(String content) {
            return message(Message.user(content));
        }

        public Builder assistant(String content) {
            return message(Message.assistant(content));
        }

        public Builder options(ChatOptions options) {
            this.options = Objects.requireNonNull(options, "options 不得为 null");
            return this;
        }

        public ChatRequest build() {
            return new ChatRequest(messages, options);
        }
    }
}
