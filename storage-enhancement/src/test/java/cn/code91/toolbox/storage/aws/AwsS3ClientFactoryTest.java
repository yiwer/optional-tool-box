package cn.code91.toolbox.storage.aws;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AwsS3ClientFactory}：凭证裁定 B——AK/SK 成对给出走 {@link StaticCredentialsProvider}
 * （MinIO 场景），否则走 SDK {@link DefaultCredentialsProvider} 链；构造期即完成校验（fail-fast）。
 */
class AwsS3ClientFactoryTest {

    @Test
    void pairedCredentialsResolveToStaticCredentialsProvider() {
        AwsS3Config config = new AwsS3Config("http://localhost:9000", "minio", true, "AKIAEXAMPLE", "secret-key");

        AwsCredentialsProvider provider = AwsS3ClientFactory.resolveCredentialsProvider(config);

        assertThat(provider).isInstanceOf(StaticCredentialsProvider.class);
        assertThat(provider.resolveCredentials().accessKeyId()).isEqualTo("AKIAEXAMPLE");
        assertThat(provider.resolveCredentials().secretAccessKey()).isEqualTo("secret-key");
    }

    @Test
    void missingCredentialsResolveToDefaultCredentialsProviderChain() {
        AwsS3Config config = new AwsS3Config(null, "us-east-1", false, null, null);

        AwsCredentialsProvider provider = AwsS3ClientFactory.resolveCredentialsProvider(config);

        assertThat(provider).isInstanceOf(DefaultCredentialsProvider.class);
    }

    @Test
    void blankCredentialsResolveToDefaultCredentialsProviderChain() {
        AwsS3Config config = new AwsS3Config(null, "us-east-1", false, "  ", "  ");

        AwsCredentialsProvider provider = AwsS3ClientFactory.resolveCredentialsProvider(config);

        assertThat(provider).isInstanceOf(DefaultCredentialsProvider.class);
    }

    @Test
    void onlyAccessKeyIdGivenFailsFastAtConstruction() {
        AwsS3Config config = new AwsS3Config(null, "us-east-1", false, "AKIAEXAMPLE", null);

        assertThatThrownBy(() -> AwsS3ClientFactory.resolveCredentialsProvider(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access-key-id")
                .hasMessageContaining("access-key-secret");
    }

    @Test
    void onlyAccessKeySecretGivenFailsFastAtConstruction() {
        AwsS3Config config = new AwsS3Config(null, "us-east-1", false, null, "secret-only");

        assertThatThrownBy(() -> AwsS3ClientFactory.resolveCredentialsProvider(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("access-key-id")
                .hasMessageContaining("access-key-secret");
    }

    @Test
    void createS3ClientBuildsUsableClientForMinioStyleConfig() {
        AwsS3Config config = new AwsS3Config("http://localhost:9000", "minio", true, "AKIAEXAMPLE", "secret-key");

        S3Client client = AwsS3ClientFactory.createS3Client(config);

        assertThat(client).isNotNull();
        client.close();
    }

    @Test
    void createS3ClientWithoutEndpointOverrideUsesRegionDefaults() {
        AwsS3Config config = new AwsS3Config(null, "us-east-1", false, "AKIAEXAMPLE", "secret-key");

        S3Client client = AwsS3ClientFactory.createS3Client(config);

        assertThat(client).isNotNull();
        client.close();
    }

    @Test
    void createS3PresignerBuildsUsablePresignerForMinioStyleConfig() {
        AwsS3Config config = new AwsS3Config("http://localhost:9000", "minio", true, "AKIAEXAMPLE", "secret-key");

        S3Presigner presigner = AwsS3ClientFactory.createS3Presigner(config);

        assertThat(presigner).isNotNull();
        presigner.close();
    }
}
