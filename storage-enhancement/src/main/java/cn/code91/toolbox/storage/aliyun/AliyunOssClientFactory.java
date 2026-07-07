package cn.code91.toolbox.storage.aliyun;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.CredentialsProviderFactory;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyuncs.exceptions.ClientException;

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
            // ecs-ram-role 配置语义是"ECS 实例绑定的 RAM 角色名"，而非鉴权服务 URL。
            // EcsRamRoleCredentialsProvider 的构造参数实为鉴权服务完整 URL（SDK 内部直接
            // new URL(...)），把角色名传入会在首次取凭证时抛 MalformedURLException，
            // 报错信息与"角色名未生效"毫无关联，具有强误导性。
            // CredentialsProviderFactory.newInstanceProfileCredentialsProvider(roleName) 才是
            // 面向角色名的正确 API：内部拼接 ECS metadata 服务 URL
            // （http://100.100.100.200/latest/meta-data/ram/security-credentials/{roleName}），
            // 且对 null/空角色名在构造期即 fail-fast（IllegalArgumentException）。工厂方法签名声明
            // 受检 ClientException 仅为与同类工厂方法（如 STS 系列）保持一致，本分支实际构造路径
            // 不会抛出；转换为 IllegalStateException 以保持本方法既有的 fail-fast 异常契约。
            try {
                return CredentialsProviderFactory.newInstanceProfileCredentialsProvider(config.ecsRamRole());
            } catch (ClientException e) {
                throw new IllegalStateException(
                        "构造阿里云 OSS ecs-ram-role 凭证提供者失败：toolbox.storage.aliyun-oss.ecs-ram-role="
                                + config.ecsRamRole(), e);
            }
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
