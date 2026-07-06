package cn.code91.toolbox.storage.aliyun;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.storage.core.AccessDenied;
import cn.code91.toolbox.storage.core.NotFound;
import cn.code91.toolbox.storage.core.ObjectKey;
import cn.code91.toolbox.storage.core.ObjectMetadata;
import cn.code91.toolbox.storage.core.PresignedUrl;
import cn.code91.toolbox.storage.core.StorageError;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSErrorCode;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.OSSObjectSummary;
import com.aliyun.oss.model.PutObjectResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AliyunOssObjectStore} 单测：aliyun adapter 不做真云 IT（任务简报明确裁定，真云冒烟进
 * USAGE 清单），故用 Mockito 模拟 {@link OSS} 客户端覆盖全部 op 与异常收敛路径，
 * 弥补"无真实后端集成测试兜底"这一覆盖缺口。
 */
@ExtendWith(MockitoExtension.class)
class AliyunOssObjectStoreTest {

    @Mock
    private OSS client;

    @Test
    void bucketNameReturnsConfiguredLogicalName() {
        AliyunOssObjectStore withClient = new AliyunOssObjectStore("images", "prod-images", client);
        assertThat(withClient.bucketName()).isEqualTo("images");
    }

    @Test
    void putDelegatesToClientAndMapsSuccessResult() {
        AliyunOssObjectStore withClient = new AliyunOssObjectStore("images", "prod-images", client);
        PutObjectResult putResult = new PutObjectResult();
        putResult.setETag("etag-123");
        when(client.putObject(eq("prod-images"), eq("a.jpg"), any(InputStream.class), any(com.aliyun.oss.model.ObjectMetadata.class)))
                .thenReturn(putResult);
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);

        Result<ObjectMetadata, StorageError> result =
                withClient.put("a.jpg", new ByteArrayInputStream(content), content.length, "image/jpeg");

        assertThat(result.isOk()).isTrue();
        assertThat(result.get().key()).isEqualTo("a.jpg");
        assertThat(result.get().etag()).isEqualTo("etag-123");
        assertThat(result.get().contentType()).isEqualTo("image/jpeg");

        ArgumentCaptor<com.aliyun.oss.model.ObjectMetadata> metadataCaptor =
                ArgumentCaptor.forClass(com.aliyun.oss.model.ObjectMetadata.class);
        verify(client).putObject(eq("prod-images"), eq("a.jpg"), any(InputStream.class), metadataCaptor.capture());
        assertThat(metadataCaptor.getValue().getContentLength()).isEqualTo(content.length);
        assertThat(metadataCaptor.getValue().getContentType()).isEqualTo("image/jpeg");
    }

    @Test
    void putMapsOssExceptionToStorageError() {
        AliyunOssObjectStore withClient = new AliyunOssObjectStore("images", "prod-images", client);
        when(client.putObject(anyString(), anyString(), any(InputStream.class), any(com.aliyun.oss.model.ObjectMetadata.class)))
                .thenThrow(ossException(OSSErrorCode.ACCESS_DENIED));

        Result<ObjectMetadata, StorageError> result =
                withClient.put("a.jpg", new ByteArrayInputStream(new byte[0]), 0, "image/jpeg");

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(AccessDenied.class);
    }

    @Test
    void getDelegatesToClientAndReturnsObjectContent() {
        AliyunOssObjectStore withClient = new AliyunOssObjectStore("images", "prod-images", client);
        OSSObject ossObject = new OSSObject();
        InputStream content = new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8));
        ossObject.setObjectContent(content);
        when(client.getObject("prod-images", "a.jpg")).thenReturn(ossObject);

        Result<InputStream, StorageError> result = withClient.get("a.jpg");

        assertThat(result.isOk()).isTrue();
        assertThat(result.get()).isSameAs(content);
    }

    @Test
    void getMapsNoSuchKeyToNotFound() {
        AliyunOssObjectStore withClient = new AliyunOssObjectStore("images", "prod-images", client);
        when(client.getObject("prod-images", "missing.jpg")).thenThrow(ossException(OSSErrorCode.NO_SUCH_KEY));

        Result<InputStream, StorageError> result = withClient.get("missing.jpg");

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(NotFound.class);
        assertThat(((NotFound) result.getErr()).key()).isEqualTo("missing.jpg");
    }

    @Test
    void listMapsObjectSummariesToObjectKeys() {
        AliyunOssObjectStore withClient = new AliyunOssObjectStore("images", "prod-images", client);
        OSSObjectSummary summary = new OSSObjectSummary();
        summary.setKey("photos/a.jpg");
        summary.setSize(100L);
        summary.setLastModified(new Date());
        ListObjectsV2Result listResult = new ListObjectsV2Result();
        listResult.addObjectSummary(summary);
        when(client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResult);

        Result<List<ObjectKey>, StorageError> result = withClient.list("photos/", 100);

        assertThat(result.isOk()).isTrue();
        assertThat(result.get()).hasSize(1);
        assertThat(result.get().get(0).key()).isEqualTo("photos/a.jpg");
        assertThat(result.get().get(0).size()).isEqualTo(100L);
    }

    @Test
    void deleteDelegatesToClient() {
        AliyunOssObjectStore withClient = new AliyunOssObjectStore("images", "prod-images", client);

        Result<Void, StorageError> result = withClient.delete("a.jpg");

        assertThat(result.isOk()).isTrue();
        verify(client).deleteObject("prod-images", "a.jpg");
    }

    @Test
    void deleteMapsClientExceptionToNetworkError() {
        AliyunOssObjectStore withClient = new AliyunOssObjectStore("images", "prod-images", client);
        com.aliyun.oss.ClientException clientException =
                new com.aliyun.oss.ClientException("timeout", "ConnectionTimeout", "req-1");
        org.mockito.Mockito.doThrow(clientException).when(client).deleteObject("prod-images", "a.jpg");

        Result<Void, StorageError> result = withClient.delete("a.jpg");

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(cn.code91.toolbox.storage.core.NetworkError.class);
    }

    @Test
    void existsDelegatesToClient() {
        AliyunOssObjectStore withClient = new AliyunOssObjectStore("images", "prod-images", client);
        when(client.doesObjectExist("prod-images", "a.jpg")).thenReturn(true);

        Result<Boolean, StorageError> result = withClient.exists("a.jpg");

        assertThat(result.isOk()).isTrue();
        assertThat(result.get()).isTrue();
    }

    @Test
    void presignedPutBuildsRequestWithPutMethodAndReturnsUrl() throws Exception {
        AliyunOssObjectStore withClient = new AliyunOssObjectStore("images", "prod-images", client);
        URL fakeUrl = java.net.URI.create("https://prod-images.oss-cn-hangzhou.aliyuncs.com/a.jpg?signature=abc").toURL();
        when(client.generatePresignedUrl(any(GeneratePresignedUrlRequest.class))).thenReturn(fakeUrl);

        Result<PresignedUrl, StorageError> result = withClient.presignedPut("a.jpg", Duration.ofMinutes(10), "image/jpeg");

        assertThat(result.isOk()).isTrue();
        assertThat(result.get().url()).isEqualTo(fakeUrl.toString());
        assertThat(result.get().method()).isEqualTo("PUT");

        ArgumentCaptor<GeneratePresignedUrlRequest> captor = ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
        verify(client).generatePresignedUrl(captor.capture());
        assertThat(captor.getValue().getMethod()).isEqualTo(HttpMethod.PUT);
        assertThat(captor.getValue().getContentType()).isEqualTo("image/jpeg");
    }

    @Test
    void presignedGetBuildsRequestWithGetMethodAndReturnsUrl() throws Exception {
        AliyunOssObjectStore withClient = new AliyunOssObjectStore("images", "prod-images", client);
        URL fakeUrl = java.net.URI.create("https://prod-images.oss-cn-hangzhou.aliyuncs.com/a.jpg?signature=xyz").toURL();
        when(client.generatePresignedUrl(eq("prod-images"), eq("a.jpg"), any(Date.class), eq(HttpMethod.GET)))
                .thenReturn(fakeUrl);

        Result<PresignedUrl, StorageError> result = withClient.presignedGet("a.jpg", Duration.ofMinutes(10));

        assertThat(result.isOk()).isTrue();
        assertThat(result.get().url()).isEqualTo(fakeUrl.toString());
        assertThat(result.get().method()).isEqualTo("GET");
    }

    private static OSSException ossException(String errorCode) {
        return new OSSException("boom", errorCode, "req-id", "host-id", "header", "resourceType", "PUT");
    }
}
