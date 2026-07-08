package cn.code91.toolbox.llm.openai;

/**
 * 模块级日志开关（{@code toolbox.llm.log.*}，全模型共享；autoconfigure 从
 * {@code ToolboxLlmProperties.Log} 转译而来）。与每模型的 {@link OpenAiModelConfig} 分离，
 * 因为日志脱敏是模块级策略而非每模型独立配置。
 *
 * @param enabled     是否记录 chat 请求/响应内容日志（总开关，默认 true）。false 时
 *                    {@link OpenAiCompatibleClient} 不产生任何请求/响应内容日志；重试告警等
 *                    运行日志不受此开关影响。
 * @param maskContent 记录 content 预览前，本模块是否显式调用 {@code MaskUtil.mask}（默认 true）。
 *                    注意：facility {@code LogUtil} 在写入前另有一层全局脱敏（默认开），故
 *                    {@code maskContent=false} 的可观察效果还取决于 {@code LogUtil} 全局设置——
 *                    本记录只约束"本模块自己这一层是否预脱敏"的语义。
 */
public record OpenAiLogSettings(boolean enabled, boolean maskContent) {

    /**
     * 默认：记录且预脱敏（全局约束 12）。供未显式传入日志配置的构造路径与既有测试使用。
     */
    public static OpenAiLogSettings defaults() {
        return new OpenAiLogSettings(true, true);
    }
}
