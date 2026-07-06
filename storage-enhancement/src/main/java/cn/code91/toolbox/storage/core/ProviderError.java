package cn.code91.toolbox.storage.core;

/**
 * 未归入其余类型的供应商侧异常（其余 S3Exception/OSSException 落此），保留供应商错误码便于排查。
 *
 * @param message      错误描述
 * @param providerCode 供应商错误码（如 S3 errorCode、OSS errorCode），无法获取时为空串
 * @param cause        原始异常（可为 null）
 */
public record ProviderError(String message, String providerCode, Throwable cause) implements StorageError {

    public static ProviderError of(String message, String providerCode, Throwable cause) {
        return new ProviderError(message, providerCode, cause);
    }
}
