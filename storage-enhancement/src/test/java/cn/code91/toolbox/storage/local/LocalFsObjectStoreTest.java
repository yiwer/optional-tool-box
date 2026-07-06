package cn.code91.toolbox.storage.local;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.storage.core.ObjectKey;
import cn.code91.toolbox.storage.core.ObjectMetadata;
import cn.code91.toolbox.storage.core.PresignedUrl;
import cn.code91.toolbox.storage.core.StorageError;
import cn.code91.toolbox.storage.core.ValidationError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * local adapter：全 op 回环、路径穿越 key 拦截、presigned NotSupported、前缀 list（05 §9）。
 */
class LocalFsObjectStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void putThenGetRoundTripsContent(@TempDir Path root) {
        LocalFsObjectStore store = new LocalFsObjectStore("images", root);
        byte[] content = "hello local adapter".getBytes(StandardCharsets.UTF_8);

        Result<ObjectMetadata, StorageError> putResult =
                store.put("a/b/photo.jpg", new ByteArrayInputStream(content), content.length, "image/jpeg");

        assertThat(putResult.isOk()).isTrue();
        ObjectMetadata metadata = putResult.get();
        assertThat(metadata.key()).isEqualTo("a/b/photo.jpg");
        assertThat(metadata.size()).isEqualTo(content.length);
        assertThat(metadata.contentType()).isEqualTo("image/jpeg");

        Result<InputStream, StorageError> getResult = store.get("a/b/photo.jpg");
        assertThat(getResult.isOk()).isTrue();
        try (InputStream in = getResult.get()) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void bucketNameReturnsConfiguredLogicalName() {
        LocalFsObjectStore store = new LocalFsObjectStore("documents", tempDir);
        assertThat(store.bucketName()).isEqualTo("documents");
    }

    @Test
    void getReturnsNotFoundForMissingKey() {
        LocalFsObjectStore store = new LocalFsObjectStore("images", tempDir);

        Result<InputStream, StorageError> result = store.get("missing.txt");

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(cn.code91.toolbox.storage.core.NotFound.class);
    }

    @Test
    void existsReflectsPutState() {
        LocalFsObjectStore store = new LocalFsObjectStore("images", tempDir);
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        assertThat(store.exists("f.txt").get()).isFalse();
        store.put("f.txt", new ByteArrayInputStream(content), content.length, "text/plain");
        assertThat(store.exists("f.txt").get()).isTrue();
    }

    @Test
    void deleteRemovesObjectAndIsIdempotent() {
        LocalFsObjectStore store = new LocalFsObjectStore("images", tempDir);
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        store.put("f.txt", new ByteArrayInputStream(content), content.length, "text/plain");

        Result<Void, StorageError> firstDelete = store.delete("f.txt");
        assertThat(firstDelete.isOk()).isTrue();
        assertThat(store.exists("f.txt").get()).isFalse();

        // 幂等：对象已不存在时再删一次仍视为成功。
        Result<Void, StorageError> secondDelete = store.delete("f.txt");
        assertThat(secondDelete.isOk()).isTrue();
    }

    @Test
    void listReturnsOnlyKeysWithMatchingPrefix() {
        LocalFsObjectStore store = new LocalFsObjectStore("images", tempDir);
        putSmall(store, "photos/a.jpg");
        putSmall(store, "photos/b.jpg");
        putSmall(store, "docs/readme.txt");

        Result<List<ObjectKey>, StorageError> result = store.list("photos/", 100);

        assertThat(result.isOk()).isTrue();
        List<String> keys = result.get().stream().map(ObjectKey::key).toList();
        assertThat(keys).containsExactlyInAnyOrder("photos/a.jpg", "photos/b.jpg");
    }

    @Test
    void listRespectsMaxKeys() {
        LocalFsObjectStore store = new LocalFsObjectStore("images", tempDir);
        putSmall(store, "a.txt");
        putSmall(store, "b.txt");
        putSmall(store, "c.txt");

        Result<List<ObjectKey>, StorageError> result = store.list("", 2);

        assertThat(result.isOk()).isTrue();
        assertThat(result.get()).hasSize(2);
    }

    @Test
    void putRejectsPathTraversalKey() {
        LocalFsObjectStore store = new LocalFsObjectStore("images", tempDir);
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        Result<ObjectMetadata, StorageError> result =
                store.put("../../etc/passwd", new ByteArrayInputStream(content), content.length, "text/plain");

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(ValidationError.class);
    }

    @Test
    void getRejectsPathTraversalKey() {
        LocalFsObjectStore store = new LocalFsObjectStore("images", tempDir);

        Result<InputStream, StorageError> result = store.get("../../etc/passwd");

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(ValidationError.class);
    }

    @Test
    void deleteRejectsPathTraversalKey() {
        LocalFsObjectStore store = new LocalFsObjectStore("images", tempDir);

        Result<Void, StorageError> result = store.delete("..\\..\\windows\\system32\\config");

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(ValidationError.class);
    }

    @Test
    void existsRejectsPathTraversalKey() {
        LocalFsObjectStore store = new LocalFsObjectStore("images", tempDir);

        Result<Boolean, StorageError> result = store.exists("../secrets.txt");

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(ValidationError.class);
    }

    @Test
    void presignedPutReturnsNotSupported() {
        LocalFsObjectStore store = new LocalFsObjectStore("images", tempDir);

        Result<PresignedUrl, StorageError> result = store.presignedPut("f.txt", Duration.ofMinutes(10), "text/plain");

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(cn.code91.toolbox.storage.core.NotSupported.class);
    }

    @Test
    void presignedGetReturnsNotSupported() {
        LocalFsObjectStore store = new LocalFsObjectStore("images", tempDir);

        Result<PresignedUrl, StorageError> result = store.presignedGet("f.txt", Duration.ofMinutes(10));

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(cn.code91.toolbox.storage.core.NotSupported.class);
    }

    @Test
    void putRejectsDangerousExtension() {
        LocalFsObjectStore store = new LocalFsObjectStore("images", tempDir);
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);

        Result<ObjectMetadata, StorageError> result =
                store.put("payload.exe", new ByteArrayInputStream(content), content.length, "application/octet-stream");

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(ValidationError.class);
    }

    private static void putSmall(LocalFsObjectStore store, String key) {
        byte[] content = "x".getBytes(StandardCharsets.UTF_8);
        Result<ObjectMetadata, StorageError> result =
                store.put(key, new ByteArrayInputStream(content), content.length, "text/plain");
        assertThat(result.isOk()).as("test fixture put should succeed for key " + key).isTrue();
    }
}
