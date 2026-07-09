package cn.code91.toolbox.llm.openai;

import java.time.Duration;

/**
 * 单个 openai-compatible 模型条目的运行期配置（内部使用，autoconfigure 从
 * {@code ToolboxLlmProperties.Model} 转译而来，同 mail {@code SmtpConnectionConfig}/
 * {@code SmtpDispatchSettings} 先例：装配绑定记录与运行期配置分离，便于测试直接构造后者）。
 *
 * @param modelName        逻辑模型名（{@code toolbox.llm.models} 下的 key，限流 key 与用量回调
 *                         的 {@code modelName} 参数均取此值，裁定 F"key 按模型名隔离"）
 * @param baseUrl          API base URL（adapter 以此构造专属 {@code RestClient}，端点固定拼接
 *                         {@code /chat/completions}；Spring {@code RestClient} 的 URI 解析自身
 *                         正确处理尾部斜杠，不需要在此额外规整）
 * @param apiKey           鉴权密钥（Bearer token；<b>绝不出现在日志/toString/错误消息</b>，全局约束 12）
 * @param model             实际下发给服务端的模型标识（如 {@code deepseek-chat}）
 * @param temperature      默认采样温度；请求级 {@code ChatOptions.temperature} 优先于此
 * @param maxTokens        默认最大生成 token 数；请求级优先于此
 * @param timeout          默认请求超时；请求级 {@code ChatOptions.timeout} 优先于此
 * @param maxRetries       默认最大重试次数（裁定 C），请求级 {@code ChatOptions.maxRetries} 优先于此
 * @param retryBackoff     首次重试退避基值，指数倍增（裁定 C）
 * @param rateLimitQps     客户端限流每秒许可数，0 = 关闭（裁定 F）
 * @param jsonMode         {@code chatStructured} 时是否附带 {@code response_format={"type":"json_object"}}
 *                         （P2 接线；仅部分 OpenAI 兼容端点支持，故为 per-model 开关；剥壳解析
 *                         无论开关与否保持鲁棒——裁定 B"可选附带"）
 */
public record OpenAiModelConfig(
        String modelName, String baseUrl, String apiKey, String model,
        Double temperature, Integer maxTokens, Duration timeout,
        int maxRetries, Duration retryBackoff, double rateLimitQps, boolean jsonMode) {

    public OpenAiModelConfig {
        if (modelName == null || modelName.isBlank()) {
            throw new IllegalArgumentException("modelName 不得为空");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "模型 \"" + modelName + "\" 缺少 base-url：请配置 toolbox.llm.models." + modelName + ".base-url");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "模型 \"" + modelName + "\" 缺少 api-key：请配置 toolbox.llm.models." + modelName + ".api-key"
                            + "（约定使用 ${ENV_VAR} 占位符，不得明文写入配置文件）");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException(
                    "模型 \"" + modelName + "\" 缺少 model：请配置 toolbox.llm.models." + modelName + ".model（服务端实际模型标识）");
        }
        if (timeout == null) {
            timeout = Duration.ofSeconds(60);
        }
        if (retryBackoff == null || retryBackoff.isNegative() || retryBackoff.isZero()) {
            retryBackoff = Duration.ofMillis(500);
        }
        if (maxRetries < 0) {
            maxRetries = 2;
        }
    }

    /**
     * 覆盖 record 默认 {@code toString()}（会逐字段输出，含 {@code apiKey} 明文）：
     * {@code apiKey} 固定替换为 {@code ******}，其余字段照常输出（全局约束 12：
     * "api-key 绝不出现在 toString/日志/错误消息"，本类当前虽无调用点整体打印自身，
     * 但作为防御性设计，避免未来新增日志语句时意外泄露）。
     */
    @Override
    public String toString() {
        return "OpenAiModelConfig[modelName=" + modelName + ", baseUrl=" + baseUrl
                + ", apiKey=******, model=" + model + ", temperature=" + temperature
                + ", maxTokens=" + maxTokens + ", timeout=" + timeout + ", maxRetries=" + maxRetries
                + ", retryBackoff=" + retryBackoff + ", rateLimitQps=" + rateLimitQps
                + ", jsonMode=" + jsonMode + "]";
    }
}
