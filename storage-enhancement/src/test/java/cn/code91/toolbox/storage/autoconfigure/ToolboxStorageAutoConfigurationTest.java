package cn.code91.toolbox.storage.autoconfigure;

import cn.code91.toolbox.storage.aliyun.AliyunOssObjectStore;
import cn.code91.toolbox.storage.aws.AwsS3ObjectStore;
import cn.code91.toolbox.storage.core.ObjectStore;
import cn.code91.toolbox.storage.core.ObjectStoreRegistry;
import cn.code91.toolbox.storage.guard.StorageGuard;
import cn.code91.toolbox.storage.local.LocalFsObjectStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 装配矩阵（05 §9）：type 三态互斥（各 type 下另两个 adapter bean 不存在）、未配 type→空
 * registry 且 {@code get()} 消息含配置引导、{@code FilteredClassLoader} 模拟缺 SDK 类→对应
 * nested 不装配、用户覆盖 {@link ObjectStoreRegistry} 让位。
 */
class ToolboxStorageAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolboxStorageAutoConfiguration.class));

    @Test
    void unconfiguredTypeAssemblesEmptyRegistryWithConfigGuidanceOnGet() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ObjectStoreRegistry.class);
            ObjectStoreRegistry registry = context.getBean(ObjectStoreRegistry.class);

            assertThatThrownBy(() -> registry.get("images"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("images")
                    .hasMessageContaining("toolbox.storage.type");
        });
    }

    @Test
    void defaultAssemblyRegistersStorageGuard() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(StorageGuard.class));
    }

    @Test
    void enabledFalseAssemblesNothing() {
        contextRunner.withPropertyValues("toolbox.storage.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ObjectStoreRegistry.class);
                    assertThat(context).doesNotHaveBean(StorageGuard.class);
                });
    }

    @Test
    void typeAwsS3AssemblesOnlyAwsS3Adapter(@TempDir Path ignored) {
        contextRunner
                .withPropertyValues(
                        "toolbox.storage.type=aws-s3",
                        "toolbox.storage.aws-s3.endpoint=http://localhost:9000",
                        "toolbox.storage.aws-s3.region=us-east-1",
                        "toolbox.storage.aws-s3.path-style-access=true",
                        "toolbox.storage.aws-s3.access-key-id=test-ak",
                        "toolbox.storage.aws-s3.access-key-secret=test-sk",
                        "toolbox.storage.aws-s3.buckets.images.name=prod-images")
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectStoreRegistry.class);
                    ObjectStoreRegistry registry = context.getBean(ObjectStoreRegistry.class);
                    ObjectStore store = registry.get("images");
                    assertThat(store).isInstanceOf(AwsS3ObjectStore.class);
                });
    }

    @Test
    void typeAliyunOssAssemblesOnlyAliyunOssAdapter() {
        contextRunner
                .withPropertyValues(
                        "toolbox.storage.type=aliyun-oss",
                        "toolbox.storage.aliyun-oss.endpoint=https://oss-cn-hangzhou.aliyuncs.com",
                        "toolbox.storage.aliyun-oss.access-key-id=test-ak",
                        "toolbox.storage.aliyun-oss.access-key-secret=test-sk",
                        "toolbox.storage.aliyun-oss.buckets.images.name=prod-images")
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectStoreRegistry.class);
                    ObjectStoreRegistry registry = context.getBean(ObjectStoreRegistry.class);
                    ObjectStore store = registry.get("images");
                    assertThat(store).isInstanceOf(AliyunOssObjectStore.class);
                });
    }

    @Test
    void typeLocalAssemblesOnlyLocalAdapter(@TempDir Path rootDir) {
        contextRunner
                .withPropertyValues(
                        "toolbox.storage.type=local",
                        "toolbox.storage.local.root-dir=" + rootDir,
                        "toolbox.storage.local.buckets.images.name=images")
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectStoreRegistry.class);
                    ObjectStoreRegistry registry = context.getBean(ObjectStoreRegistry.class);
                    ObjectStore store = registry.get("images");
                    assertThat(store).isInstanceOf(LocalFsObjectStore.class);
                });
    }

    @Test
    void localTypeRequiresNoCloudSdkOnClasspath(@TempDir Path rootDir) {
        // local 装配无 L1（@ConditionalOnClass）：两个云 SDK 均被 FilteredClassLoader 屏蔽时仍应装配成功
        // （00-overview.md §7 依赖策略："两个云 SDK 均缺失时：local type 仍完整可用"）。
        contextRunner
                .withClassLoader(new FilteredClassLoader(
                        "software.amazon.awssdk.services.s3.S3Client", "com.aliyun.oss.OSS"))
                .withPropertyValues(
                        "toolbox.storage.type=local",
                        "toolbox.storage.local.root-dir=" + rootDir,
                        "toolbox.storage.local.buckets.images.name=images")
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectStoreRegistry.class);
                    ObjectStore store = context.getBean(ObjectStoreRegistry.class).get("images");
                    assertThat(store).isInstanceOf(LocalFsObjectStore.class);
                });
    }

    @Test
    void filteredAwsSdkClassPreventsAwsNestedConfigurationFromAssembling() {
        contextRunner
                .withClassLoader(new FilteredClassLoader("software.amazon.awssdk.services.s3.S3Client"))
                .withPropertyValues(
                        "toolbox.storage.type=aws-s3",
                        "toolbox.storage.aws-s3.access-key-id=test-ak",
                        "toolbox.storage.aws-s3.access-key-secret=test-sk")
                .run(context -> {
                    // L1 缺类：aws-s3 的 nested 不装配，退回 L4 兜底空 registry（未知桶抛配置引导异常，
                    // 而非启动失败）——门面在缺 SDK 时仍可加载正是 L1 条件装配的设计意图。
                    assertThat(context).hasSingleBean(ObjectStoreRegistry.class);
                    ObjectStoreRegistry registry = context.getBean(ObjectStoreRegistry.class);
                    assertThatThrownBy(() -> registry.get("images")).isInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    void filteredAliyunSdkClassPreventsAliyunNestedConfigurationFromAssembling() {
        contextRunner
                .withClassLoader(new FilteredClassLoader("com.aliyun.oss.OSS"))
                .withPropertyValues(
                        "toolbox.storage.type=aliyun-oss",
                        "toolbox.storage.aliyun-oss.access-key-id=test-ak",
                        "toolbox.storage.aliyun-oss.access-key-secret=test-sk")
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectStoreRegistry.class);
                    ObjectStoreRegistry registry = context.getBean(ObjectStoreRegistry.class);
                    assertThatThrownBy(() -> registry.get("images")).isInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    void userDefinedObjectStoreRegistryTakesPrecedence() {
        contextRunner.withUserConfiguration(CustomRegistryConfig.class).run(context -> {
            assertThat(context).hasSingleBean(ObjectStoreRegistry.class);
            assertThat(context.getBean(ObjectStoreRegistry.class)).isInstanceOf(CustomRegistryConfig.StubRegistry.class);
        });
    }

    @Test
    void userDefinedStorageGuardTakesPrecedence() {
        contextRunner.withUserConfiguration(CustomGuardConfig.class).run(context -> {
            assertThat(context).hasSingleBean(StorageGuard.class);
            assertThat(context.getBean(StorageGuard.class)).isInstanceOf(CustomGuardConfig.StubGuard.class);
        });
    }

    @Configuration
    static class CustomRegistryConfig {
        @Bean
        ObjectStoreRegistry objectStoreRegistry() {
            return new StubRegistry();
        }

        static final class StubRegistry implements ObjectStoreRegistry {
            @Override
            public ObjectStore get(String logicalBucketName) {
                throw new UnsupportedOperationException();
            }
        }
    }

    @Configuration
    static class CustomGuardConfig {
        @Bean
        StorageGuard storageGuard() {
            return new StubGuard();
        }

        static final class StubGuard implements StorageGuard {
            @Override
            public cn.code91.facility.result.Result<Void, cn.code91.toolbox.storage.core.StorageError> check(
                    cn.code91.toolbox.storage.guard.UploadCandidate candidate) {
                return cn.code91.facility.result.Result.ok();
            }
        }
    }
}
