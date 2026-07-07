package cn.code91.toolbox.storage.aws;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

/**
 * AWS S3（及一切 S3 兼容：MinIO/腾讯 COS/华为 OBS）客户端构造，凭证裁定 B：
 * {@code access-key-id}/{@code access-key-secret} 成对给出走
 * {@link StaticCredentialsProvider}（MinIO 场景），否则走 SDK
 * {@link DefaultCredentialsProvider} 链；两者只给一个视为配置错误，构造期即 fail-fast。
 */
public final class AwsS3ClientFactory {

    private AwsS3ClientFactory() {
    }

    public static S3Client createS3Client(AwsS3Config config) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(config.region()))
                .credentialsProvider(resolveCredentialsProvider(config))
                .forcePathStyle(config.pathStyleAccess());
        applyEndpointOverride(config, builder::endpointOverride);
        return builder.build();
    }

    public static S3Presigner createS3Presigner(AwsS3Config config) {
        // S3Presigner.Builder 没有 forcePathStyle 直达方法（不同于 S3ClientBuilder），
        // path-style 需经 serviceConfiguration(S3Configuration) 显式设置；否则 MinIO 场景下
        // 签出的 URL 会是虚拟主机风格（<bucket>.<host>），MinIO 默认不解析该风格导致 DNS 解析失败。
        S3Presigner.Builder builder = S3Presigner.builder()
                .region(Region.of(config.region()))
                .credentialsProvider(resolveCredentialsProvider(config))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(config.pathStyleAccess())
                        .build());
        applyEndpointOverride(config, builder::endpointOverride);
        return builder.build();
    }

    private static void applyEndpointOverride(AwsS3Config config, java.util.function.Consumer<URI> setter) {
        if (config.endpoint() != null && !config.endpoint().isBlank()) {
            setter.accept(URI.create(config.endpoint()));
        }
    }

    /**
     * 解析凭证提供者；包可见以便单测直接验证选择逻辑，不必真的发起网络请求。
     *
     * @throws IllegalStateException access-key-id/access-key-secret 只给出一个时抛出
     *                                （成对约定被打破，视为配置错误，fail-fast）
     */
    static AwsCredentialsProvider resolveCredentialsProvider(AwsS3Config config) {
        boolean hasId = hasText(config.accessKeyId());
        boolean hasSecret = hasText(config.accessKeySecret());
        if (hasId && hasSecret) {
            return StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(config.accessKeyId(), config.accessKeySecret()));
        }
        if (hasId != hasSecret) {
            throw new IllegalStateException(
                    "toolbox.storage.aws-s3.access-key-id 与 toolbox.storage.aws-s3.access-key-secret 必须成对给出，"
                            + "当前只配置了其中一个；请补全两者，或都不配置以改走 SDK 默认凭证链");
        }
        // .create() 在本 SDK 版本已标记过时，.builder().build() 是等价的推荐替代写法。
        return DefaultCredentialsProvider.builder().build();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
