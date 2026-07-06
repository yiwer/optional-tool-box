package cn.code91.toolbox.storage.aws;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.storage.core.ObjectKey;
import cn.code91.toolbox.storage.core.ObjectMetadata;
import cn.code91.toolbox.storage.core.PresignedUrl;
import cn.code91.toolbox.storage.core.StorageError;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AwsS3ObjectStore} 集成测试：Testcontainers {@link MinIOContainer}，全 op 回环 +
 * presignedPut/Get 用 {@link HttpClient} 真实 PUT/GET 断言内容一致（05 §9）。
 *
 * <p>MinIO 即"S3 兼容"路径的持续验证（endpoint + path-style-access=true，00-overview.md §7
 * 测试策略）；无 Docker 时整体跳过（{@code disabledWithoutDocker=true}），不影响无 Docker 环境
 * 下的常规 {@code mvn test}。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class AwsS3ObjectStoreIT {

    private static final String PHYSICAL_BUCKET = "it-bucket";

    @Container
    private static final MinIOContainer MINIO =
            new MinIOContainer("minio/minio:RELEASE.2024-01-16T16-07-38Z");

    private static S3Client client;
    private static S3Presigner presigner;
    private static AwsS3ObjectStore store;

    @BeforeAll
    static void setUpClientAndBucket() {
        AwsS3Config config = new AwsS3Config(
                MINIO.getS3URL(), "us-east-1", true, MINIO.getUserName(), MINIO.getPassword());
        client = AwsS3ClientFactory.createS3Client(config);
        presigner = AwsS3ClientFactory.createS3Presigner(config);
        client.createBucket(b -> b.bucket(PHYSICAL_BUCKET));
        store = new AwsS3ObjectStore("images", PHYSICAL_BUCKET, client, presigner);
    }

    @AfterAll
    static void tearDownClient() {
        if (presigner != null) {
            presigner.close();
        }
        if (client != null) {
            client.close();
        }
    }

    @Test
    void putThenGetRoundTripsContent() throws IOException {
        byte[] content = "minio integration test content".getBytes(StandardCharsets.UTF_8);

        Result<ObjectMetadata, StorageError> putResult =
                store.put("docs/report.txt", new ByteArrayInputStream(content), content.length, "text/plain");

        assertThat(putResult.isOk()).as("put 应成功：%s", putResult).isTrue();
        assertThat(putResult.get().key()).isEqualTo("docs/report.txt");

        Result<InputStream, StorageError> getResult = store.get("docs/report.txt");
        assertThat(getResult.isOk()).isTrue();
        try (InputStream in = getResult.get()) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void existsReflectsPutState() {
        byte[] content = "exists-check".getBytes(StandardCharsets.UTF_8);
        String key = "exists/flag.txt";

        assertThat(store.exists(key).get()).isFalse();
        store.put(key, new ByteArrayInputStream(content), content.length, "text/plain");
        assertThat(store.exists(key).get()).isTrue();
    }

    @Test
    void deleteRemovesObject() {
        byte[] content = "to-delete".getBytes(StandardCharsets.UTF_8);
        String key = "delete/me.txt";
        store.put(key, new ByteArrayInputStream(content), content.length, "text/plain");
        assertThat(store.exists(key).get()).isTrue();

        Result<Void, StorageError> deleteResult = store.delete(key);

        assertThat(deleteResult.isOk()).isTrue();
        assertThat(store.exists(key).get()).isFalse();
    }

    @Test
    void getMissingKeyReturnsNotFound() {
        Result<InputStream, StorageError> result = store.get("does/not/exist.txt");

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(cn.code91.toolbox.storage.core.NotFound.class);
    }

    @Test
    void listReturnsKeysWithMatchingPrefix() {
        store.put("listing/a.txt", new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)), 1, "text/plain");
        store.put("listing/b.txt", new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8)), 1, "text/plain");
        store.put("other/c.txt", new ByteArrayInputStream("c".getBytes(StandardCharsets.UTF_8)), 1, "text/plain");

        Result<List<ObjectKey>, StorageError> result = store.list("listing/", 100);

        assertThat(result.isOk()).isTrue();
        List<String> keys = result.get().stream().map(ObjectKey::key).toList();
        assertThat(keys).contains("listing/a.txt", "listing/b.txt").doesNotContain("other/c.txt");
    }

    @Test
    void presignedPutAllowsRealHttpUploadThenPresignedGetDownloadsSameContent() throws IOException, InterruptedException {
        String key = "presigned/upload.txt";
        byte[] content = "uploaded via presigned PUT over real HTTP".getBytes(StandardCharsets.UTF_8);
        HttpClient httpClient = HttpClient.newHttpClient();

        Result<PresignedUrl, StorageError> putUrlResult =
                store.presignedPut(key, Duration.ofMinutes(10), "text/plain");
        assertThat(putUrlResult.isOk()).as("presignedPut 应成功：%s", putUrlResult).isTrue();
        PresignedUrl putUrl = putUrlResult.get();
        assertThat(putUrl.method()).isEqualTo("PUT");

        HttpRequest putRequest = HttpRequest.newBuilder(URI.create(putUrl.url()))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        HttpResponse<Void> putResponse = httpClient.send(putRequest, HttpResponse.BodyHandlers.discarding());
        assertThat(putResponse.statusCode()).as("真实 HTTP PUT 应成功").isBetween(200, 299);

        Result<PresignedUrl, StorageError> getUrlResult = store.presignedGet(key, Duration.ofMinutes(10));
        assertThat(getUrlResult.isOk()).isTrue();
        PresignedUrl getUrl = getUrlResult.get();
        assertThat(getUrl.method()).isEqualTo("GET");

        HttpRequest getRequest = HttpRequest.newBuilder(URI.create(getUrl.url())).GET().build();
        HttpResponse<byte[]> getResponse = httpClient.send(getRequest, HttpResponse.BodyHandlers.ofByteArray());

        assertThat(getResponse.statusCode()).as("真实 HTTP GET 应成功").isBetween(200, 299);
        assertThat(getResponse.body()).isEqualTo(content);
    }
}
