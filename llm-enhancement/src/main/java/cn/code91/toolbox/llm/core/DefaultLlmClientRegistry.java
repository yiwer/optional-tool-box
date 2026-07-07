package cn.code91.toolbox.llm.core;

import java.util.Map;

/**
 * {@link LlmClientRegistry} 默认实现：不可变 map 查找。未知模型名抛
 * {@link IllegalArgumentException}，消息含 {@code toolbox.llm.models} 配置引导
 * （00 §3.3 空 Registry 兜底，同 storage/mail 空 registry 模式）。
 * primary 解析（裁定 D）：显式 {@code toolbox.llm.primary} &gt; 唯一模型 &gt; 报错
 * （与 mail 的三级解析不同——llm 天然多模型共存，没有类似 "spring 收编" 的兜底账号，
 * 多模型又未显式指定 primary 时只能报错，不能瞎猜哪个是"默认"）。
 */
public final class DefaultLlmClientRegistry implements LlmClientRegistry {

    private final Map<String, LlmClient> clients;
    private final String explicitPrimary;

    /**
     * @param clients         逻辑模型名 → 客户端；零模型时传空 map
     * @param explicitPrimary {@code toolbox.llm.primary} 显式配置值，未配置为 null
     */
    public DefaultLlmClientRegistry(Map<String, LlmClient> clients, String explicitPrimary) {
        this.clients = Map.copyOf(clients);
        this.explicitPrimary = explicitPrimary;
    }

    @Override
    public LlmClient get(String modelName) {
        LlmClient client = clients.get(modelName);
        if (client == null) {
            throw new IllegalArgumentException(guidanceForUnknown(modelName));
        }
        return client;
    }

    @Override
    public LlmClient primary() {
        if (explicitPrimary != null && !explicitPrimary.isBlank()) {
            LlmClient client = clients.get(explicitPrimary);
            if (client == null) {
                throw new IllegalArgumentException(
                        "toolbox.llm.primary=\"" + explicitPrimary + "\" 指向不存在的模型（当前已配置模型："
                                + clients.keySet() + "）；请改为其中之一，或在 toolbox.llm.models."
                                + explicitPrimary + " 下补齐该模型");
            }
            return client;
        }
        if (clients.size() == 1) {
            return clients.values().iterator().next();
        }
        if (clients.isEmpty()) {
            throw new IllegalArgumentException(bootstrapGuidance("（默认模型）"));
        }
        throw new IllegalArgumentException(
                "存在多个模型（" + clients.keySet() + "）且未指定默认模型，无法解析 primary()；"
                        + "请配置 toolbox.llm.primary 指定其中之一");
    }

    private String guidanceForUnknown(String modelName) {
        if (clients.isEmpty()) {
            return bootstrapGuidance("\"" + modelName + "\"");
        }
        return "未找到模型 \"" + modelName + "\"：请在 toolbox.llm.models." + modelName
                + " 下声明该模型（type/base-url/api-key/model…）；当前已配置模型：" + clients.keySet();
    }

    /**
     * 零模型兜底引导（裁定 D）。
     */
    private static String bootstrapGuidance(String requested) {
        return "未找到模型 " + requested + "：当前未配置任何大模型。请在 toolbox.llm.models.<模型名> 下"
                + "声明模型（type: openai-compatible / base-url / api-key / model…）";
    }
}
