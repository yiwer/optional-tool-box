package cn.code91.toolbox.storage.aliyun;

import cn.code91.toolbox.storage.core.AccessDenied;
import cn.code91.toolbox.storage.core.NetworkError;
import cn.code91.toolbox.storage.core.NotFound;
import cn.code91.toolbox.storage.core.ProviderError;
import cn.code91.toolbox.storage.core.StorageError;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSSErrorCode;
import com.aliyun.oss.OSSException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AliyunOssErrorMapper}：构造真实 SDK 异常实例做映射表穷尽单测（05 §9）。
 * 映射表（任务简报裁定）：NoSuchKey/404→NotFound；InvalidAccessKeyId/403→AccessDenied；
 * ClientException→NetworkError；其余 OSSException→ProviderError。
 */
class AliyunOssErrorMapperTest {

    private final AliyunOssErrorMapper mapper = new AliyunOssErrorMapper();

    @Test
    void mapsNoSuchKeyToNotFound() {
        OSSException exception = ossException(OSSErrorCode.NO_SUCH_KEY);

        StorageError mapped = mapper.map(exception, "photos/a.jpg");

        assertThat(mapped).isInstanceOf(NotFound.class);
        assertThat(((NotFound) mapped).key()).isEqualTo("photos/a.jpg");
    }

    @Test
    void mapsInvalidAccessKeyIdToAccessDenied() {
        OSSException exception = ossException(OSSErrorCode.INVALID_ACCESS_KEY_ID);

        StorageError mapped = mapper.map(exception, "photos/a.jpg");

        assertThat(mapped).isInstanceOf(AccessDenied.class);
    }

    @Test
    void mapsAccessDeniedCodeToAccessDenied() {
        OSSException exception = ossException(OSSErrorCode.ACCESS_DENIED);

        StorageError mapped = mapper.map(exception, "photos/a.jpg");

        assertThat(mapped).isInstanceOf(AccessDenied.class);
    }

    @Test
    void mapsOtherOssExceptionToProviderErrorWithErrorCode() {
        OSSException exception = ossException(OSSErrorCode.INTERNAL_ERROR);

        StorageError mapped = mapper.map(exception, "photos/a.jpg");

        assertThat(mapped).isInstanceOf(ProviderError.class);
        assertThat(((ProviderError) mapped).providerCode()).isEqualTo(OSSErrorCode.INTERNAL_ERROR);
        assertThat(((ProviderError) mapped).cause()).isSameAs(exception);
    }

    @Test
    void mapsClientExceptionToNetworkError() {
        ClientException exception = new ClientException("connection refused", "ConnectionTimeout", "req-1");

        StorageError mapped = mapper.map(exception, "photos/a.jpg");

        assertThat(mapped).isInstanceOf(NetworkError.class);
        assertThat(((NetworkError) mapped).cause()).isSameAs(exception);
    }

    @Test
    void mapsGenericRuntimeExceptionToProviderError() {
        // 兜底分支：非 OSSException/ClientException 的其余运行时异常（如序列化异常）也不应外抛。
        IllegalStateException exception = new IllegalStateException("unexpected");

        StorageError mapped = mapper.map(exception, "photos/a.jpg");

        assertThat(mapped).isInstanceOf(ProviderError.class);
        assertThat(((ProviderError) mapped).cause()).isSameAs(exception);
    }

    private static OSSException ossException(String errorCode) {
        return new OSSException("boom", errorCode, "req-id", "host-id", "header", "resourceType", "PUT");
    }
}
