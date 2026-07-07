package cn.code91.toolbox.llm.spi;

import cn.code91.toolbox.llm.core.Usage;

import java.time.Duration;

/**
 * 用量观测回调 SPI（L5：应用 {@code @Bean} 即被 {@code ObjectProvider} 收集，全部回调，
 * 03 §4.4）。回调抛出的异常会被 adapter 吞掉并记日志，<b>不影响 chat 主流程与返回结果</b>
 * （同 mail {@code MailSendListener} 先例）。
 */
public interface UsageListener {

    /**
     * 一次成功的模型调用后回调。
     *
     * @param modelName 逻辑模型名（{@code toolbox.llm.models} 下的 key）
     * @param usage     本次调用的 token 用量
     * @param latency   本次调用耗时（含重试等待，即调用方视角的端到端耗时）
     * @param traceId   facility {@code TraceIdFilter} 的 MDC traceId；不可用时为 null
     */
    void onUsage(String modelName, Usage usage, Duration latency, String traceId);
}
