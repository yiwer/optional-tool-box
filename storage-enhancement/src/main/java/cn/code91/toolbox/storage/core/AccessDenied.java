package cn.code91.toolbox.storage.core;

/**
 * 凭证或权限问题（如 403、{@code InvalidAccessKeyId}），调用方通常应告警而非重试。
 *
 * @param key     相关对象 key（无具体对象时为空串）
 * @param message 错误描述
 */
public record AccessDenied(String key, String message) implements StorageError {

    public static AccessDenied of(String key, String message) {
        return new AccessDenied(key, message);
    }
}
