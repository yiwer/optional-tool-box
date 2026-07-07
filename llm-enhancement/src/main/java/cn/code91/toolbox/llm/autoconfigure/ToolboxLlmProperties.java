package cn.code91.toolbox.llm.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.Map;

/**
 * {@code toolbox.llm.*} 配置（03 §6 全字段）。
 *
 * @param enabled 模块总开关（实际装配与否由 {@link ToolboxLlmAutoConfiguration} 类上的
 *                {@code @ConditionalOnProperty(matchIfMissing = true)} 决定，本字段仅供
 *                配置元数据展示，同 compare/storage/mail 先例）
 * @param primary 默认模型名（裁定 D 三级解析第一级：显式 primary &gt; 唯一模型 &gt; 报错）
 * @param log     请求/响应日志脱敏参数
 * @param models  逻辑模型名 → 模型配置
 */
@ConfigurationProperties(prefix = "toolbox.llm")
public record ToolboxLlmProperties(
        boolean enabled,
        String primary,
        @NestedConfigurationProperty Log log,
        Map<String, Model> models) {

    public ToolboxLlmProperties {
        models = models == null ? Map.of() : Map.copyOf(models);
        if (log == null) {
            log = Log.defaults();
        }
    }

    /**
     * 日志脱敏参数（全局约束 12：默认开）。
     *
     * @param enabled     是否记录请求/响应日志（整体开关，默认开）
     * @param maskContent 是否对提示词/响应内容脱敏（{@code MaskUtil}，默认开）；仅在
     *                    {@code enabled=true} 时有意义
     */
    public record Log(@DefaultValue("true") boolean enabled, @DefaultValue("true") boolean maskContent) {

        static Log defaults() {
            return new Log(true, true);
        }
    }

    /**
     * 单个模型条目（03 §6）。仅支持 {@code type: openai-compatible}（P1 唯一实现）；未来
     * 新增 provider 类型时按 {@code type} 分派构造不同 adapter（03 §4.5 "按 type 实例化"）。
     *
     * @param type          adapter 类型标识，P1 恒为 {@code openai-compatible}
     * @param baseUrl       API base URL（必填，装配期 fail-fast，裁定 D）
     * @param apiKey        鉴权密钥（必填，装配期 fail-fast；<b>强制 {@code ${ENV_VAR}} 占位符
     *                      约定</b>，不落任何日志/错误消息，全局约束 12）
     * @param model         服务端实际模型标识（必填，装配期 fail-fast）
     * @param temperature   默认采样温度；未配置由请求级 {@code ChatOptions} 决定
     * @param maxTokens     默认最大生成 token 数
     * @param timeout       请求超时；未配置回退默认 60s
     * @param maxRetries    最大重试次数（裁定 C）；未配置回退默认 2
     * @param rateLimitQps  客户端限流每秒许可数，0=关闭（裁定 F）
     */
    public record Model(
            String type, String baseUrl, String apiKey, String model,
            Double temperature, Integer maxTokens, Duration timeout,
            Integer maxRetries, Double rateLimitQps) {

        public Model {
            if (type == null || type.isBlank()) {
                type = "openai-compatible";
            }
        }
    }
}
