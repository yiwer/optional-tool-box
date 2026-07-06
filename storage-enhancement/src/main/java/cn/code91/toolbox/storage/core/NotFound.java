package cn.code91.toolbox.storage.core;

/**
 * 对象不存在（如 S3 {@code NoSuchKey}、OSS 404）。
 *
 * @param key     未找到的对象 key
 * @param message 错误描述
 */
public record NotFound(String key, String message) implements StorageError {

    public static NotFound of(String key) {
        return new NotFound(key, "对象不存在：" + key);
    }
}
