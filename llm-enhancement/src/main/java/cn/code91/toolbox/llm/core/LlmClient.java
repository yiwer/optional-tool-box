package cn.code91.toolbox.llm.core;

import cn.code91.facility.result.Result;

/**
 * 大模型对话 Seam（03 §4.1）。<b>裁定 A 明确的 P1 接口边界</b>：仅 {@link #chat} 与
 * {@link #chatStructured} 两个方法——不含 {@code chatStream}（SSE 流式需要独立基建，P2 引入）、
 * 不含 {@code chatAsync}（P2）。实现负责限速、脱敏日志、重试与用量回调的完整编排；
 * 一切失败以 {@link LlmError} 错误值返回，公开 API 零异常外抛。
 */
public interface LlmClient {

    /**
     * 同步对话。
     */
    Result<ChatResponse, LlmError> chat(ChatRequest request);

    /**
     * 结构化输出：提示词需自行指示模型以 JSON 形式作答（P1 不依赖厂商 "JSON mode" 开关，
     * 裁定 B），响应文本经剥壳与鲁棒解析后反序列化为 {@code targetType}；解析或校验失败
     * 返回 {@code Err(SchemaMismatch)} 并携带模型原始文本。
     *
     * @param targetType 目标强类型（建议使用 record，Jackson 可直接反序列化）
     */
    <T> Result<T, LlmError> chatStructured(ChatRequest request, Class<T> targetType);
}
