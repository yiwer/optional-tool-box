package cn.code91.toolbox.storage.aliyun;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.auth.EcsRamRoleCredentialsProvider;

/**
 * 阿里云 OSS 客户端构造，凭证裁定 B：{@code ecs-ram-role} 优先，否则 AK/SK，
 * 两者皆缺在工厂构造期抛 {@link IllegalStateException}（消息含配置路径），fail-fast。
 */
public final class AliyunOssClientFactory {

    private AliyunOssClientFactory() {
    }

    public static OSS createClient(AliyunOssConfig config) {
        return OSSClientBuilder.create()
                .endpoint(config.endpoint())
                .credentialsProvider(resolveCredentialsProvider(config))
                .build();
    }

    /**
     * 解析凭证提供者；包可见以便单测直接验证选择逻辑。
     *
     * @throws IllegalStateException ecs-ram-role 与 AK/SK 均未（完整）配置时抛出
     */
    static CredentialsProvider resolveCredentialsProvider(AliyunOssConfig config) {
        if (hasText(config.ecsRamRole())) {
            return new EcsRamRoleCredentialsProvider(config.ecsRamRole());
        }
        boolean hasId = hasText(config.accessKeyId());
        boolean hasSecret = hasText(config.accessKeySecret());
        if (hasId && hasSecret) {
            return new DefaultCredentialProvider(config.accessKeyId(), config.accessKeySecret());
        }
        if (hasId != hasSecret) {
            throw new IllegalStateException(
                    "toolbox.storage.aliyun-oss.access-key-id 与 toolbox.storage.aliyun-oss.access-key-secret "
                            + "必须成对给出，当前只配置了其中一个");
        }
        throw new IllegalStateException(
                "阿里云 OSS 凭证未配置：请设置 toolbox.storage.aliyun-oss.ecs-ram-role（优先），"
                        + "或成对设置 toolbox.storage.aliyun-oss.access-key-id 与 "
                        + "toolbox.storage.aliyun-oss.access-key-secret");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
