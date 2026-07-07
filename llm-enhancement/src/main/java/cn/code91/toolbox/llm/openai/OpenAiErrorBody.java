package cn.code91.toolbox.llm.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI 兼容协议的错误响应体（内部使用，不外泄）：{@code {"error": {"message","type","code"}}}。
 * 各厂商字段名基本一致（DeepSeek/Moonshot/GLM/DashScope 兼容模式均沿用此形态），
 * {@code type}/{@code code} 两个字段各厂商取舍不一，解析时两者都尝试取值。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAiErrorBody(@JsonProperty("error") Detail error) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Detail(
            @JsonProperty("message") String message,
            @JsonProperty("type") String type,
            @JsonProperty("code") String code) {
    }
}
