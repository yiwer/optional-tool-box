package cn.code91.toolbox.compare.core;

/**
 * 差异对比过程中的错误类型，穷尽 5 种子类型，调用方可 pattern matching 完全处理。
 */
public sealed interface CompareError
        permits TypeMismatch, DepthExceeded, CycleDetected, FieldAccessError, RenderError {

    /**
     * 错误描述信息。
     */
    String message();

    /**
     * 出错位置的字段路径（形如 {@code "amount"}、{@code "address.city"}）；
     * 无法定位到具体字段时（如渲染错误）为空串。
     */
    String path();
}
