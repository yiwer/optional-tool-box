package cn.code91.toolbox.llm.autoconfigure;

import cn.code91.toolbox.llm.core.DefaultLlmClientRegistry;
import cn.code91.toolbox.llm.core.LlmClient;
import cn.code91.toolbox.llm.core.LlmClientRegistry;
import cn.code91.toolbox.llm.openai.OpenAiCompatibleClient;
import cn.code91.toolbox.llm.openai.OpenAiLogSettings;
import cn.code91.toolbox.llm.openai.OpenAiModelConfig;
import cn.code91.toolbox.llm.prompt.ClasspathPromptTemplateLoader;
import cn.code91.toolbox.llm.spi.PromptTemplateLoader;
import cn.code91.toolbox.llm.spi.UsageListener;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * llm-enhancement 唯一装配入口（brief 交付物）。装配条件分层（00 §3.2）：P1 无 L1 条件
 * （OpenAI 兼容 adapter 零第三方依赖，03 §5）；L2 总开关默认开；L4 两个 bean 均
 * {@code @ConditionalOnMissingBean} 可被应用覆盖；L5 {@code ObjectProvider} 收集
 * {@link UsageListener}。启动期 fail-fast（裁定 D）由 {@link OpenAiModelConfig} 紧凑构造器
 * 完成——模型条目缺 base-url/api-key/model 会在此处构造期抛 {@code IllegalStateException}
 * 阻断容器刷新。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "toolbox.llm", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ToolboxLlmProperties.class)
public class ToolboxLlmAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public PromptTemplateLoader toolboxLlmPromptTemplateLoader() {
        return new ClasspathPromptTemplateLoader();
    }

    /**
     * 模型注册表装配（裁定 D）：遍历 {@code toolbox.llm.models}，按 {@code type} 构造 adapter
     * （P1 仅 {@code openai-compatible} 一种实现）并织入限流/脱敏日志/重试/用量回调（均在
     * {@link OpenAiCompatibleClient} 内部完成，同 mail {@code SmtpMailDispatcher} 单类编排
     * 先例）；零模型装配空 registry 兜底。
     */
    @Bean
    @ConditionalOnMissingBean
    public LlmClientRegistry llmClientRegistry(ToolboxLlmProperties properties,
                                               ObjectProvider<UsageListener> listeners) {
        List<UsageListener> listenerList = listeners.orderedStream().toList();
        OpenAiLogSettings logSettings =
                new OpenAiLogSettings(properties.log().enabled(), properties.log().maskContent());
        Map<String, LlmClient> clients = new LinkedHashMap<>();
        properties.models().forEach((name, model) ->
                clients.put(name, buildClient(name, model, listenerList, logSettings)));
        return new DefaultLlmClientRegistry(clients, properties.primary());
    }

    /**
     * {@code type} 分派点（03 §4.5："每个 model 条目按其 type 实例化对应 adapter"）。P1 仅
     * {@code openai-compatible}；未识别的 {@code type} 视为配置错误，启动期 fail-fast
     * （同一裁定 D 精神：宁可拦在启动期，不留到运行期才发现配错）。
     */
    private static LlmClient buildClient(String name, ToolboxLlmProperties.Model model,
                                         List<UsageListener> listeners, OpenAiLogSettings logSettings) {
        if (!"openai-compatible".equals(model.type())) {
            throw new IllegalStateException(
                    "模型 \"" + name + "\" 配置了未识别的 type=\"" + model.type()
                            + "\"；toolbox.llm.models." + name + ".type 当前 P1 仅支持 openai-compatible");
        }
        OpenAiModelConfig config = new OpenAiModelConfig(
                name, model.baseUrl(), model.apiKey(), model.model(),
                model.temperature(), model.maxTokens(), model.timeout(),
                model.maxRetries() == null ? -1 : model.maxRetries(),
                (Duration) null, model.rateLimitQps() == null ? 0 : model.rateLimitQps(),
                Boolean.TRUE.equals(model.jsonMode()));
        return new OpenAiCompatibleClient(config, listeners, logSettings);
    }
}
