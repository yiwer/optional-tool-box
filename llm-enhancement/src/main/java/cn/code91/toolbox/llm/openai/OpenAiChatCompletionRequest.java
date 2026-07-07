package cn.code91.toolbox.llm.openai;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * {@code POST /chat/completions} 请求体（内部使用，不外泄）。可选字段为 null 时不序列化
 * （{@link JsonInclude.Include#NON_NULL}），避免把 "未设置" 误发成 "显式设为 null" 让部分
 * 兼容端点拒绝请求。
 *
 * @param responseFormat 裁定 B：配置支持时附带的 JSON mode 提示（P1 不依赖它，解析始终鲁棒），
 *                       未配置附带则为 null 不下发
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record OpenAiChatCompletionRequest(
        @JsonProperty("model") String model,
        @JsonProperty("messages") List<OpenAiChatMessage> messages,
        @JsonProperty("temperature") Double temperature,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("response_format") OpenAiResponseFormat responseFormat) {

    record OpenAiResponseFormat(@JsonProperty("type") String type) {

        static final OpenAiResponseFormat JSON_OBJECT = new OpenAiResponseFormat("json_object");
    }
}
