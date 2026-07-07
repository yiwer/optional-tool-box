package cn.code91.toolbox.llm.core;

import java.util.Objects;

/**
 * 单条对话消息（03 §4.1）。
 *
 * @param role    消息角色
 * @param content 消息内容（不得为 null；空串合法，如占位系统消息）
 */
public record Message(Role role, String content) {

    public Message {
        Objects.requireNonNull(role, "role 不得为 null");
        Objects.requireNonNull(content, "content 不得为 null");
    }

    /**
     * 便捷构造：系统消息。
     */
    public static Message system(String content) {
        return new Message(Role.SYSTEM, content);
    }

    /**
     * 便捷构造：用户消息。
     */
    public static Message user(String content) {
        return new Message(Role.USER, content);
    }

    /**
     * 便捷构造：助手消息（多轮对话回填历史轮次时使用）。
     */
    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content);
    }
}
