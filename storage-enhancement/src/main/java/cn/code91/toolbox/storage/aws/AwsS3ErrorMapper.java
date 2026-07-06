package cn.code91.toolbox.storage.aws;

import cn.code91.toolbox.storage.core.AccessDenied;
import cn.code91.toolbox.storage.core.NetworkError;
import cn.code91.toolbox.storage.core.NotFound;
import cn.code91.toolbox.storage.core.ProviderError;
import cn.code91.toolbox.storage.core.StorageError;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;

/**
 * AWS S3 SDK v2 异常 → {@link StorageError} 的映射，收敛于此、单测穷尽（任务简报裁定）。
 *
 * <p>映射表：{@link NoSuchKeyException} → {@link NotFound}；HTTP 403 或 errorCode
 * {@code AccessDenied} → {@link AccessDenied}；{@link SdkClientException}
 * （网络层，构造/发送请求前后的连接性问题，与 {@code S3Exception} 是并列分支，前者是
 * {@code SdkException} 直接子类而非 {@code AwsServiceException} 子类）及 {@link IOException}
 * 类 → {@link NetworkError}；其余 {@link S3Exception} → {@link ProviderError} 带 errorCode。</p>
 */
public final class AwsS3ErrorMapper {

    /**
     * 将捕获到的异常映射为 {@link StorageError}；{@code key} 用于 {@link NotFound}/
     * {@link AccessDenied} 携带相关对象 key（无具体 key 语境时传空串）。
     */
    public StorageError map(Exception exception, String key) {
        if (exception instanceof NoSuchKeyException) {
            return NotFound.of(key);
        }
        if (exception instanceof S3Exception s3Exception) {
            return mapS3Exception(s3Exception, key);
        }
        if (exception instanceof SdkClientException sdkClientException) {
            return NetworkError.of("S3 客户端网络异常：" + sdkClientException.getMessage(), sdkClientException);
        }
        if (exception instanceof IOException ioException) {
            return NetworkError.of("S3 操作发生 IO 异常：" + ioException.getMessage(), ioException);
        }
        // 兜底：非 SDK 已知类型的运行时异常同样不得外抛，归入 ProviderError。
        return ProviderError.of("S3 操作发生未分类异常：" + exception.getMessage(), "", exception);
    }

    private StorageError mapS3Exception(S3Exception exception, String key) {
        String errorCode = errorCode(exception);
        if (exception.statusCode() == 403 || "AccessDenied".equals(errorCode)) {
            return AccessDenied.of(key, "S3 拒绝访问（statusCode=" + exception.statusCode() + "）：" + exception.getMessage());
        }
        return ProviderError.of("S3 服务端异常：" + exception.getMessage(), errorCode == null ? "" : errorCode, exception);
    }

    private static String errorCode(S3Exception exception) {
        AwsErrorDetails details = exception.awsErrorDetails();
        return details == null ? null : details.errorCode();
    }
}
