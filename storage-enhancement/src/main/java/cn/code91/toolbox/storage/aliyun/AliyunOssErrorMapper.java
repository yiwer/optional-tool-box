package cn.code91.toolbox.storage.aliyun;

import cn.code91.toolbox.storage.core.AccessDenied;
import cn.code91.toolbox.storage.core.NetworkError;
import cn.code91.toolbox.storage.core.NotFound;
import cn.code91.toolbox.storage.core.ProviderError;
import cn.code91.toolbox.storage.core.StorageError;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSErrorCode;
import com.aliyun.oss.OSSException;

/**
 * 阿里云 OSS SDK 异常 → {@link StorageError} 的映射，收敛于此、单测穷尽（任务简报裁定）。
 *
 * <p>映射表：{@code NoSuchKey}/404 → {@link NotFound}；{@code InvalidAccessKeyId}/403 →
 * {@link AccessDenied}；{@link ClientException}（客户端侧网络/连接异常，与
 * {@link OSSException} 是并列的两个独立类型，不存在继承关系）→ {@link NetworkError}；
 * 其余 {@link OSSException} → {@link ProviderError} 带 errorCode。</p>
 *
 * <p>披露：OSS SDK 3.18.1 的异常体系（{@code ServiceException}/{@code OSSException}/
 * {@code ClientException}）均不暴露 HTTP 状态码访问器，"403" 只能通过
 * {@link OSSErrorCode#ACCESS_DENIED}/{@link OSSErrorCode#INVALID_ACCESS_KEY_ID}/
 * {@link OSSErrorCode#ACCESS_FORBIDDEN} 等错误码字符串间接识别，无法按 HTTP 状态码本身判断。</p>
 */
public final class AliyunOssErrorMapper {

    /**
     * 将捕获到的异常映射为 {@link StorageError}；{@code key} 用于 {@link NotFound}/
     * {@link AccessDenied} 携带相关对象 key（无具体 key 语境时传空串）。
     */
    public StorageError map(Exception exception, String key) {
        if (exception instanceof OSSException ossException) {
            return mapOssException(ossException, key);
        }
        if (exception instanceof ClientException clientException) {
            return NetworkError.of("OSS 客户端网络异常：" + clientException.getMessage(), clientException);
        }
        // 兜底：非 SDK 已知类型的运行时异常同样不得外抛，归入 ProviderError。
        return ProviderError.of("OSS 操作发生未分类异常：" + exception.getMessage(), "", exception);
    }

    private StorageError mapOssException(OSSException exception, String key) {
        String errorCode = exception.getErrorCode();
        if (OSSErrorCode.NO_SUCH_KEY.equals(errorCode)) {
            return NotFound.of(key);
        }
        if (OSSErrorCode.INVALID_ACCESS_KEY_ID.equals(errorCode)
                || OSSErrorCode.ACCESS_DENIED.equals(errorCode)
                || OSSErrorCode.ACCESS_FORBIDDEN.equals(errorCode)) {
            return AccessDenied.of(key, "OSS 拒绝访问（errorCode=" + errorCode + "）：" + exception.getErrorMessage());
        }
        return ProviderError.of("OSS 服务端异常：" + exception.getErrorMessage(), errorCode, exception);
    }
}
