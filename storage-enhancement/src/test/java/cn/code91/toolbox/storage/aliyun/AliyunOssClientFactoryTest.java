package cn.code91.toolbox.storage.aliyun;

import com.aliyun.oss.OSS;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.auth.InstanceProfileCredentialsProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AliyunOssClientFactory}：凭证裁定 B——{@code ecs-ram-role} 优先，否则 AK/SK，
 * 两者皆缺在工厂构造期抛 {@link IllegalStateException}（消息含配置路径）。
 */
class AliyunOssClientFactoryTest {

    @Test
    void ecsRamRoleTakesPrecedenceOverAkSk() {
        AliyunOssConfig config = new AliyunOssConfig(
                "https://oss-cn-hangzhou.aliyuncs.com", "prod-app-role", "AK", "SK");

        CredentialsProvider provider = AliyunOssClientFactory.resolveCredentialsProvider(config);

        // ecs-ram-role 语义是"ECS 绑定的 RAM 角色名"，而非鉴权服务 URL；
        // 必须走 InstanceProfileCredentialsProvider（内部拼 ECS metadata URL），
        // 而非 EcsRamRoleCredentialsProvider（其构造参数是鉴权服务完整 URL，
        // 直接把角色名传入会在 SDK 内部 new URL(...) 时抛 MalformedURLException）。
        assertThat(provider).isInstanceOf(InstanceProfileCredentialsProvider.class);
    }

    @Test
    void fallsBackToAkSkWhenEcsRamRoleAbsent() {
        AliyunOssConfig config = new AliyunOssConfig(
                "https://oss-cn-hangzhou.aliyuncs.com", null, "AK", "SK");

        CredentialsProvider provider = AliyunOssClientFactory.resolveCredentialsProvider(config);

        assertThat(provider).isInstanceOf(DefaultCredentialProvider.class);
    }

    @Test
    void blankEcsRamRoleFallsBackToAkSk() {
        AliyunOssConfig config = new AliyunOssConfig(
                "https://oss-cn-hangzhou.aliyuncs.com", "   ", "AK", "SK");

        CredentialsProvider provider = AliyunOssClientFactory.resolveCredentialsProvider(config);

        assertThat(provider).isInstanceOf(DefaultCredentialProvider.class);
    }

    @Test
    void missingBothEcsRamRoleAndAkSkFailsFastWithConfigGuidance() {
        AliyunOssConfig config = new AliyunOssConfig("https://oss-cn-hangzhou.aliyuncs.com", null, null, null);

        assertThatThrownBy(() -> AliyunOssClientFactory.resolveCredentialsProvider(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toolbox.storage.aliyun-oss.ecs-ram-role")
                .hasMessageContaining("toolbox.storage.aliyun-oss.access-key-id");
    }

    @Test
    void onlyAccessKeyIdGivenFailsFastWithConfigGuidance() {
        AliyunOssConfig config = new AliyunOssConfig("https://oss-cn-hangzhou.aliyuncs.com", null, "AK", null);

        assertThatThrownBy(() -> AliyunOssClientFactory.resolveCredentialsProvider(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access-key-secret");
    }

    @Test
    void createClientBuildsUsableClient() {
        AliyunOssConfig config = new AliyunOssConfig("https://oss-cn-hangzhou.aliyuncs.com", null, "AK", "SK");

        OSS client = AliyunOssClientFactory.createClient(config);

        assertThat(client).isNotNull();
        client.shutdown();
    }
}
