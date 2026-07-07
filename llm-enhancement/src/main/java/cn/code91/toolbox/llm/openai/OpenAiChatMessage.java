package cn.code91.toolbox.llm.openai;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI 兼容协议的对话消息 DTO（内部使用，不外泄，03 §4.5）。
 */
record OpenAiChatMessage(@JsonProperty("role") String role, @JsonProperty("content") String content) {
}
