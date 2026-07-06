package cn.code91.toolbox.storage.aliyun;

import com.aliyun.oss.OSS;
import com.aliyun.oss.common.auth.CredentialsProvider;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.auth.EcsRamRoleCredentialsProvider;
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

        assertThat(provider).isInstanceOf(EcsRamRoleCredentialsProvider.class);
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
