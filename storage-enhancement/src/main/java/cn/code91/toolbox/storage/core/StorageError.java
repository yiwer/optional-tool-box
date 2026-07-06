package cn.code91.toolbox.storage.core;

/**
 * 对象存储操作的错误类型，穷尽 6 种子类型（P1 范围，裁定 A：分片相关的 {@code MultipartError}
 * 随 P2 一并加入），调用方可 pattern matching 完全处理。
 */
public sealed interface StorageError
        permits NotFound, AccessDenied, NetworkError, ValidationError, ProviderError, NotSupported {

    /**
     * 错误描述信息。
     */
    String message();
}
