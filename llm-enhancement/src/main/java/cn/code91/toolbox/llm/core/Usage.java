package cn.code91.toolbox.llm.core;

/**
 * token 用量（03 §4.1），供 {@link cn.code91.toolbox.llm.spi.UsageListener} 成本统计。
 *
 * @param promptTokens     提示词消耗
 * @param completionTokens 生成内容消耗
 * @param totalTokens      合计（一般等于前两者之和，但保留服务端原样返回值，不重算）
 */
public record Usage(int promptTokens, int completionTokens, int totalTokens) {

    /**
     * 全零用量：错误路径或用量字段缺失时的兜底占位（不代表"真实发生了 0 token 的调用"）。
     */
    public static Usage zero() {
        return new Usage(0, 0, 0);
    }
}
