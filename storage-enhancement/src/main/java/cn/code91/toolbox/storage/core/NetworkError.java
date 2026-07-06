package cn.code91.toolbox.storage.core;

/**
 * 网络/连接类异常（如 SDK 客户端异常、IO 异常），通常可重试。
 *
 * @param message 错误描述
 * @param cause   原始异常（可为 null）
 */
public record NetworkError(String message, Throwable cause) implements StorageError {

    public static NetworkError of(String message, Throwable cause) {
        return new NetworkError(message, cause);
    }
}
