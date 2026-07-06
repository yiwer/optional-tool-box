package cn.code91.toolbox.storage.aws;

import cn.code91.toolbox.storage.core.AccessDenied;
import cn.code91.toolbox.storage.core.NetworkError;
import cn.code91.toolbox.storage.core.NotFound;
import cn.code91.toolbox.storage.core.ProviderError;
import cn.code91.toolbox.storage.core.StorageError;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AwsS3ErrorMapper}：构造真实 SDK 异常实例做映射表穷尽单测（05 §9）。
 * 映射表（任务简报裁定）：NoSuchKeyException→NotFound；403/AccessDenied→AccessDenied；
 * SdkClientException 及 IO 类→NetworkError；其余 S3Exception→ProviderError 带 errorCode。
 */
class AwsS3ErrorMapperTest {

    private final AwsS3ErrorMapper mapper = new AwsS3ErrorMapper();

    @Test
    void mapsNoSuchKeyExceptionToNotFound() {
        NoSuchKeyException exception = NoSuchKeyException.builder()
                .message("The specified key does not exist")
                .statusCode(404)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("NoSuchKey").errorMessage("not found").build())
                .build();

        StorageError mapped = mapper.map(exception, "photos/a.jpg");

        assertThat(mapped).isInstanceOf(NotFound.class);
        assertThat(((NotFound) mapped).key()).isEqualTo("photos/a.jpg");
    }

    @Test
    void mapsHttp403StatusToAccessDenied() {
        S3Exception exception = (S3Exception) S3Exception.builder()
                .message("Forbidden")
                .statusCode(403)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("SomeOtherCode").errorMessage("forbidden").build())
                .build();

        StorageError mapped = mapper.map(exception, "photos/a.jpg");

        assertThat(mapped).isInstanceOf(AccessDenied.class);
    }

    @Test
    void mapsAccessDeniedErrorCodeToAccessDenied() {
        // 即便 statusCode 未被设置为 403（如 mock/构造场景漏设），errorCode="AccessDenied" 仍应识别。
        S3Exception exception = (S3Exception) S3Exception.builder()
                .message("Access Denied")
                .statusCode(0)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("AccessDenied").errorMessage("denied").build())
                .build();

        StorageError mapped = mapper.map(exception, "photos/a.jpg");

        assertThat(mapped).isInstanceOf(AccessDenied.class);
    }

    @Test
    void mapsOtherS3ExceptionToProviderErrorWithErrorCode() {
        S3Exception exception = (S3Exception) S3Exception.builder()
                .message("Internal error")
                .statusCode(500)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("InternalError").errorMessage("boom").build())
                .build();

        StorageError mapped = mapper.map(exception, "photos/a.jpg");

        assertThat(mapped).isInstanceOf(ProviderError.class);
        assertThat(((ProviderError) mapped).providerCode()).isEqualTo("InternalError");
        assertThat(((ProviderError) mapped).cause()).isSameAs(exception);
    }

    @Test
    void mapsSdkClientExceptionToNetworkError() {
        SdkClientException exception = SdkClientException.create("Unable to execute HTTP request: connect timed out");

        StorageError mapped = mapper.map(exception, "photos/a.jpg");

        assertThat(mapped).isInstanceOf(NetworkError.class);
        assertThat(((NetworkError) mapped).cause()).isSameAs(exception);
    }

    @Test
    void mapsIOExceptionToNetworkError() {
        java.io.IOException exception = new java.io.IOException("connection reset");

        StorageError mapped = mapper.map(exception, "photos/a.jpg");

        assertThat(mapped).isInstanceOf(NetworkError.class);
        assertThat(((NetworkError) mapped).cause()).isSameAs(exception);
    }

    @Test
    void mapsGenericRuntimeExceptionToProviderError() {
        IllegalStateException exception = new IllegalStateException("unexpected");

        StorageError mapped = mapper.map(exception, "photos/a.jpg");

        assertThat(mapped).isInstanceOf(ProviderError.class);
        assertThat(((ProviderError) mapped).cause()).isSameAs(exception);
    }
}
