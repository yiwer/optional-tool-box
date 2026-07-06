package cn.code91.toolbox.storage.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DefaultObjectStoreRegistry}：按逻辑桶名查找，未知桶名抛带配置引导的异常。
 */
class DefaultObjectStoreRegistryTest {

    @Test
    void getReturnsStoreForKnownLogicalBucket() {
        ObjectStore images = new StubObjectStore("images");
        DefaultObjectStoreRegistry registry = new DefaultObjectStoreRegistry(Map.of("images", images), "aws-s3");

        assertThat(registry.get("images")).isSameAs(images);
    }

    @Test
    void getThrowsWithConfigGuidanceForUnknownLogicalBucket() {
        DefaultObjectStoreRegistry registry =
                new DefaultObjectStoreRegistry(Map.of("images", new StubObjectStore("images")), "aws-s3");

        assertThatThrownBy(() -> registry.get("documents"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("documents")
                .hasMessageContaining("toolbox.storage.aws-s3.buckets");
    }

    @Test
    void emptyRegistryThrowsWithConfigGuidance() {
        DefaultObjectStoreRegistry registry = new DefaultObjectStoreRegistry(Map.of(), null);

        assertThatThrownBy(() -> registry.get("images"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("images")
                .hasMessageContaining("toolbox.storage");
    }

    /**
     * 最小 {@link ObjectStore} 桩实现，仅用于验证 registry 的查找语义，不涉及任何存储操作。
     */
    private record StubObjectStore(String bucketName) implements ObjectStore {

        @Override
        public cn.code91.facility.result.Result<ObjectMetadata, StorageError> put(
                String key, java.io.InputStream data, long contentLength, String contentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public cn.code91.facility.result.Result<java.io.InputStream, StorageError> get(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public cn.code91.facility.result.Result<java.util.List<ObjectKey>, StorageError> list(String prefix, int maxKeys) {
            throw new UnsupportedOperationException();
        }

        @Override
        public cn.code91.facility.result.Result<Void, StorageError> delete(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public cn.code91.facility.result.Result<Boolean, StorageError> exists(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public cn.code91.facility.result.Result<PresignedUrl, StorageError> presignedPut(
                String key, java.time.Duration expires, String contentType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public cn.code91.facility.result.Result<PresignedUrl, StorageError> presignedGet(String key, java.time.Duration expires) {
            throw new UnsupportedOperationException();
        }
    }
}
