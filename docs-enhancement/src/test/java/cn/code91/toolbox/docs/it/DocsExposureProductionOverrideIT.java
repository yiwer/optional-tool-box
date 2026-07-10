package cn.code91.toolbox.docs.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产 profile + 显式 {@code expose-in-production=true}（裁定 D）：恢复 200。
 * 与 {@link DocsExposureProductionIT} 配对：同一应用、同一 profile，仅此一项配置之差，
 * 证明彼处 404 出自门禁过滤器而非 handler 缺失。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = DocsItApplication.class,
        properties = {
                "spring.main.banner-mode=off",
                "toolbox.docs.exposure.expose-in-production=true",
                // 与 DocsExposureProductionIT 同配分组：yaml 变体端点（含分组）在显式打开时恢复 200，
                // 配对证明未打开时的 404 出自门禁过滤器（裁定 D 修订）。
                "toolbox.docs.groups.admin.paths-to-match=/admin/**"
        })
@ActiveProfiles("prod")
class DocsExposureProductionOverrideIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void apiDocsIsExposedAgainWithExplicitOptIn() {
        assertThat(restTemplate.getForEntity("/v3/api-docs", String.class).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void apiDocsYamlVariantEndpointsAreExposedAgainWithExplicitOptIn() {
        assertThat(restTemplate.getForEntity("/v3/api-docs.yaml", String.class).getStatusCode().value())
                .isEqualTo(200);
        assertThat(restTemplate.getForEntity("/v3/api-docs.yaml/admin", String.class).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    void exportEndpointIsExposedAgainWithExplicitOptIn() {
        assertThat(restTemplate.getForEntity("/toolbox/docs/export?format=json", String.class)
                .getStatusCode().value()).isEqualTo(200);
    }
}
