package cn.code91.toolbox.llm.core;

import java.time.Duration;

/**
 * 单次请求可选参数（03 §4.1）。全部字段可空——为 null 时由 adapter 回退到该模型的配置默认值
 * （{@code toolbox.llm.models.<name>.*}），请求级显式值优先于模型级配置。
 *
 * @param temperature 采样温度；null 则不随请求下发，由服务端/模型配置默认值决定
 * @param maxTokens   最大生成 token 数；null 同上
 * @param timeout     本次请求的超时时长；null 时回退模型配置的 {@code timeout}
 * @param maxRetries  本次请求的最大重试次数（裁定 C）；null 时回退模型配置的 {@code max-retries}
 */
public record ChatOptions(Double temperature, Integer maxTokens, Duration timeout, Integer maxRetries) {

    private static final ChatOptions DEFAULTS = new ChatOptions(null, null, null, null);

    /**
     * 全空选项（便捷构造，如 {@link ChatRequest#user(String)}）。
     */
    public static ChatOptions defaults() {
        return DEFAULTS;
    }
}
