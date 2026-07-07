package cn.code91.toolbox.llm.core;

/**
 * 多模型注册表（Registry + 逻辑名模式，00 §3.3）。未配置任何模型时装配"空 Registry"兜底：
 * 应用能启动，取模型时才抛带配置引导的异常（裁定 D）。
 */
public interface LlmClientRegistry {

    /**
     * 按逻辑模型名取客户端。
     *
     * @throws IllegalArgumentException 模型不存在时抛出，消息含 {@code toolbox.llm.models} 配置引导
     */
    LlmClient get(String modelName);

    /**
     * 默认模型（裁定 D 三级解析：显式 {@code toolbox.llm.primary} &gt; 唯一模型 &gt; 报错）。
     *
     * @throws IllegalArgumentException 无法解析默认模型时抛出，消息含配置引导
     */
    LlmClient primary();
}
