package cn.code91.toolbox.llm.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code POST /chat/completions} 成功响应体（内部使用，不外泄）。
 * {@link JsonIgnoreProperties}(ignoreUnknown=true) 容忍各兼容端点私有扩展字段
 * （如 DashScope 的 {@code request_id}、Moonshot 的额外 usage 明细）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAiChatCompletionResponse(
        @JsonProperty("model") String model,
        @JsonProperty("choices") List<Choice> choices,
        @JsonProperty("usage") OpenAiUsage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(
            @JsonProperty("message") OpenAiChatMessage message,
            @JsonProperty("finish_reason") String finishReason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record OpenAiUsage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens) {
    }
}
