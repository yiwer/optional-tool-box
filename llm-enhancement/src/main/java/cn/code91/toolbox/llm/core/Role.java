package cn.code91.toolbox.llm.core;

/**
 * 对话消息角色（03 §4.1）。openai adapter 负责将其映射为协议要求的小写字符串
 * （{@code system}/{@code user}/{@code assistant}），core 包本身不涉及协议细节。
 */
public enum Role {

    SYSTEM,
    USER,
    ASSISTANT
}
