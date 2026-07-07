package cn.code91.toolbox.storage.autoconfigure;

import cn.code91.toolbox.storage.aliyun.AliyunOssClientFactory;
import cn.code91.toolbox.storage.aliyun.AliyunOssConfig;
import cn.code91.toolbox.storage.aliyun.AliyunOssObjectStore;
import cn.code91.toolbox.storage.aws.AwsS3ClientFactory;
import cn.code91.toolbox.storage.aws.AwsS3Config;
import cn.code91.toolbox.storage.aws.AwsS3ObjectStore;
import cn.code91.toolbox.storage.core.DefaultObjectStoreRegistry;
import cn.code91.toolbox.storage.core.ObjectStore;
import cn.code91.toolbox.storage.core.ObjectStoreRegistry;
import cn.code91.toolbox.storage.guard.DefaultStorageGuard;
import cn.code91.toolbox.storage.guard.GuardConfig;
import cn.code91.toolbox.storage.guard.StorageGuard;
import cn.code91.toolbox.storage.local.LocalFsObjectStore;
import com.aliyun.oss.OSS;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * storage-enhancement 自动装配入口（05 设计文档 §5）。L2 总开关默认 true；未配置
 * {@code toolbox.storage.type} 时装配空 registry 兜底（应用能启动，{@code get()} 时才报错并
 * 附配置引导）；{@code type} 三态经 nested {@code @Configuration} + {@code @ConditionalOnProperty}
 * 互斥，aws-s3/aliyun-oss 两个 nested 类另加 {@code @ConditionalOnClass}（字符串形式，缺 SDK 类不装配）。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "toolbox.storage", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ToolboxStorageProperties.class)
public class ToolboxStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ObjectStoreRegistry toolboxStorageEmptyRegistry() {
        return new DefaultObjectStoreRegistry(Map.of(), null);
    }

    @Bean
    @ConditionalOnMissingBean
    public StorageGuard toolboxStorageGuard(ToolboxStorageProperties properties) {
        ToolboxStorageProperties.Guard guard = properties.guard();
        long maxSize = guard.maxSize() == null ? 0L : guard.maxSize().toBytes();
        return new DefaultStorageGuard(new GuardConfig(maxSize, guard.blockedExtensions(), guard.verifyMime()));
    }

    /**
     * L3 互斥①：{@code type=aws-s3}。另加 L1（{@code @ConditionalOnClass}，字符串形式探测
     * {@code S3Client}，消费方未引入 SDK 时本配置整体不装配，门面仍可加载）。
     *
     * <p><b>I3 修复</b>：{@code client}/{@code presigner} 此前是 registry {@code @Bean}
     * 方法体内的局部对象，Spring 推断不到销毁方法，上下文关闭时连接池/线程泄漏。现各自独立成
     * {@code @Bean}（{@code @ConditionalOnMissingBean}，可被应用覆盖——附带收益：client/presigner
     * 成为可覆盖的 Seam），registry 方法转为注入这两个 bean，不再自行构造。AWS SDK v2 客户端
     * 实现 {@code SdkAutoCloseable}/{@code AutoCloseable}（{@code close()}），Spring 默认的
     * inferred 销毁模式即可探测到，无需显式 {@code destroyMethod}。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "toolbox.storage", name = "type", havingValue = "aws-s3")
    @ConditionalOnClass(name = "software.amazon.awssdk.services.s3.S3Client")
    static class AwsS3Configuration {

        @Bean
        @ConditionalOnMissingBean
        S3Client toolboxStorageAwsS3Client(ToolboxStorageProperties properties) {
            // factory 构造期即完成凭证校验（fail-fast，凭证裁定 B）；在此立即构造而非延迟到
            // 首次 get()，保证配置错误在启动期暴露。
            return AwsS3ClientFactory.createS3Client(toAwsS3Config(properties));
        }

        @Bean
        @ConditionalOnMissingBean
        S3Presigner toolboxStorageAwsS3Presigner(ToolboxStorageProperties properties) {
            return AwsS3ClientFactory.createS3Presigner(toAwsS3Config(properties));
        }

        @Bean
        @ConditionalOnMissingBean
        ObjectStoreRegistry toolboxStorageAwsS3Registry(
                ToolboxStorageProperties properties, S3Client client, S3Presigner presigner) {
            ToolboxStorageProperties.AwsS3 awsS3 = properties.awsS3();
            Map<String, ObjectStore> stores = new LinkedHashMap<>();
            awsS3.buckets().forEach((logicalName, bucketProps) ->
                    stores.put(logicalName, new AwsS3ObjectStore(logicalName, bucketProps.name(), client, presigner)));
            return new DefaultObjectStoreRegistry(stores, "aws-s3");
        }

        private static AwsS3Config toAwsS3Config(ToolboxStorageProperties properties) {
            ToolboxStorageProperties.AwsS3 awsS3 = properties.awsS3();
            return new AwsS3Config(
                    awsS3.endpoint(), awsS3.region(), awsS3.pathStyleAccess(),
                    awsS3.accessKeyId(), awsS3.accessKeySecret());
        }
    }

    /**
     * L3 互斥②：{@code type=aliyun-oss}。另加 L1（探测 {@code com.aliyun.oss.OSS}）。
     *
     * <p><b>I3 修复</b>：{@code client} 同样独立成 {@code @Bean}。{@link OSS} 不实现
     * {@code AutoCloseable}，其关闭方法是 {@code shutdown()}——虽然 Spring inferred 模式
     * 也会探测名为 {@code shutdown} 的方法，仍显式声明 {@code destroyMethod="shutdown"}
     * 以确保确定性、不依赖推断细节。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "toolbox.storage", name = "type", havingValue = "aliyun-oss")
    @ConditionalOnClass(name = "com.aliyun.oss.OSS")
    static class AliyunOssConfiguration {

        @Bean(destroyMethod = "shutdown")
        @ConditionalOnMissingBean
        OSS toolboxStorageAliyunOssClient(ToolboxStorageProperties properties) {
            ToolboxStorageProperties.AliyunOss aliyunOss = properties.aliyunOss();
            AliyunOssConfig config = new AliyunOssConfig(
                    aliyunOss.endpoint(), aliyunOss.ecsRamRole(), aliyunOss.accessKeyId(), aliyunOss.accessKeySecret());
            // factory 构造期即完成凭证校验（fail-fast，凭证裁定 B）。
            return AliyunOssClientFactory.createClient(config);
        }

        @Bean
        @ConditionalOnMissingBean
        ObjectStoreRegistry toolboxStorageAliyunOssRegistry(ToolboxStorageProperties properties, OSS client) {
            ToolboxStorageProperties.AliyunOss aliyunOss = properties.aliyunOss();
            Map<String, ObjectStore> stores = new LinkedHashMap<>();
            aliyunOss.buckets().forEach((logicalName, bucketProps) ->
                    stores.put(logicalName, new AliyunOssObjectStore(logicalName, bucketProps.name(), client)));
            return new DefaultObjectStoreRegistry(stores, "aliyun-oss");
        }
    }

    /**
     * L3 互斥③：{@code type=local}。无 L1（零 SDK 依赖，本地开发场景需在两个云 SDK 均缺失时
     * 仍完整可用）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "toolbox.storage", name = "type", havingValue = "local")
    static class LocalConfiguration {

        @Bean
        @ConditionalOnMissingBean
        ObjectStoreRegistry toolboxStorageLocalRegistry(ToolboxStorageProperties properties) {
            ToolboxStorageProperties.Local local = properties.local();
            if (local.rootDir() == null || local.rootDir().isBlank()) {
                throw new IllegalStateException(
                        "toolbox.storage.type=local 但未配置 toolbox.storage.local.root-dir，"
                                + "请指定本地存储根目录");
            }
            Path rootDir = Path.of(local.rootDir());
            return new DefaultObjectStoreRegistry(buildLocalStores(rootDir, local), "local");
        }

        /**
         * {@link LocalFsObjectStore} 构造函数以"子目录名"作为其内部 bucketName（同时也是
         * root-dir 下的子目录名）；registry 对外仍以逻辑桶名 {@code logicalName} 索引，
         * 二者可不同（{@link ToolboxStorageProperties.BucketProperties#name()} 未配置时回退为
         * 逻辑桶名本身，与 aws-s3/aliyun-oss 的"物理桶名可与逻辑名不同"保持同一心智模型）。
         */
        private static Map<String, ObjectStore> buildLocalStores(Path rootDir, ToolboxStorageProperties.Local local) {
            Map<String, ObjectStore> stores = new LinkedHashMap<>();
            local.buckets().forEach((logicalName, bucketProps) -> {
                String subDir = (bucketProps.name() == null || bucketProps.name().isBlank())
                        ? logicalName : bucketProps.name();
                stores.put(logicalName, new LocalFsObjectStore(subDir, rootDir));
            });
            return stores;
        }
    }
}
