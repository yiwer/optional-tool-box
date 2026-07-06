package cn.code91.toolbox.storage.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.util.unit.DataSize;

import java.util.Map;
import java.util.Set;

/**
 * {@code toolbox.storage.*} 配置（05 设计文档 §6）。
 *
 * @param enabled  模块总开关（实际装配与否由 {@link ToolboxStorageAutoConfiguration} 类上的
 *                 {@code @ConditionalOnProperty(matchIfMissing = true)} 决定，本字段仅供配置元数据展示，
 *                 同 compare-enhancement 先例）
 * @param type     供应商选型：{@code aws-s3}/{@code aliyun-oss}/{@code local}；未配置时装配空 registry
 * @param guard    上传守卫参数
 * @param awsS3    aws-s3 连接参数（type=aws-s3 时生效）
 * @param aliyunOss aliyun-oss 连接参数（type=aliyun-oss 时生效）
 * @param local    local 连接参数（type=local 时生效）
 */
@ConfigurationProperties(prefix = "toolbox.storage")
public record ToolboxStorageProperties(
        boolean enabled,
        String type,
        @NestedConfigurationProperty Guard guard,
        @NestedConfigurationProperty AwsS3 awsS3,
        @NestedConfigurationProperty AliyunOss aliyunOss,
        @NestedConfigurationProperty Local local) {

    public ToolboxStorageProperties {
        if (guard == null) {
            guard = Guard.defaults();
        }
        if (awsS3 == null) {
            awsS3 = AwsS3.empty();
        }
        if (aliyunOss == null) {
            aliyunOss = AliyunOss.empty();
        }
        if (local == null) {
            local = Local.empty();
        }
    }

    /**
     * 逻辑桶名 → 物理桶名映射的配置条目（{@code toolbox.storage.<type>.buckets.<logicalName>.name}）。
     *
     * @param name 物理桶名（aws-s3/aliyun-oss）或不使用（local 场景，逻辑桶名即子目录名）
     */
    public record BucketProperties(String name) {
    }

    /**
     * 上传守卫参数（05 §6）。
     *
     * @param maxSize           最大允许大小；未配置时不限制
     * @param blockedExtensions 配置叠加的扩展名黑名单（与 facility 内置危险扩展名并集）
     * @param verifyMime        是否启用魔数嗅探与声明 Content-Type 比对
     */
    public record Guard(DataSize maxSize, Set<String> blockedExtensions, boolean verifyMime) {

        public Guard {
            blockedExtensions = blockedExtensions == null ? Set.of() : Set.copyOf(blockedExtensions);
        }

        static Guard defaults() {
            return new Guard(null, Set.of(), false);
        }
    }

    /**
     * aws-s3 连接参数。
     *
     * @param endpoint        自定义 endpoint（MinIO/COS/OBS 兼容场景）
     * @param region          AWS region
     * @param pathStyleAccess 是否启用 path-style 访问
     * @param accessKeyId     可选静态凭证 AK（凭证裁定 B：与 accessKeySecret 成对给出才生效）
     * @param accessKeySecret 可选静态凭证 SK
     * @param buckets         逻辑桶名 → 物理桶名
     */
    public record AwsS3(
            String endpoint,
            String region,
            boolean pathStyleAccess,
            String accessKeyId,
            String accessKeySecret,
            Map<String, BucketProperties> buckets) {

        public AwsS3 {
            buckets = buckets == null ? Map.of() : Map.copyOf(buckets);
        }

        static AwsS3 empty() {
            return new AwsS3(null, null, false, null, null, Map.of());
        }
    }

    /**
     * aliyun-oss 连接参数。
     *
     * @param endpoint        OSS endpoint
     * @param ecsRamRole      ECS RAM 角色名；优先于 AK/SK（凭证裁定 B）
     * @param accessKeyId     可选静态凭证 AK
     * @param accessKeySecret 可选静态凭证 SK
     * @param buckets         逻辑桶名 → 物理桶名
     */
    public record AliyunOss(
            String endpoint,
            String ecsRamRole,
            String accessKeyId,
            String accessKeySecret,
            Map<String, BucketProperties> buckets) {

        public AliyunOss {
            buckets = buckets == null ? Map.of() : Map.copyOf(buckets);
        }

        static AliyunOss empty() {
            return new AliyunOss(null, null, null, null, Map.of());
        }
    }

    /**
     * local 连接参数。
     *
     * @param rootDir 本地存储根目录；每个逻辑桶在其下建子目录（目录名取 {@link BucketProperties#name()}，
     *                未配置 name 时回退为逻辑桶名本身）
     * @param buckets 逻辑桶名 → 子目录名；与 aws-s3/aliyun-oss 保持一致的"仅已声明逻辑桶可用"契约
     *                （未声明的逻辑桶名调用 {@code get()} 同样抛带配置引导的异常，而非任意目录名自动生效）
     */
    public record Local(String rootDir, Map<String, BucketProperties> buckets) {

        public Local {
            buckets = buckets == null ? Map.of() : Map.copyOf(buckets);
        }

        static Local empty() {
            return new Local(null, Map.of());
        }
    }
}
