package cn.code91.toolbox.docs.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产 profile 默认不暴露（裁定 D）：api-docs、swagger-ui、导出端点全 404。
 * swagger-ui 未引 UI 构件本就无 handler；api-docs 与 export 的 handler 真实存在——
 * 由 {@link DocsExposureProductionOverrideIT}（同一应用仅差 expose-in-production=true 时 200）
 * 配对证明此处的 404 出自门禁过滤器而非 handler 缺失。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = DocsItApplication.class,
        properties = {
                "spring.main.banner-mode=off",
                // 配置一个分组，使 springdoc 的分组 yaml 变体端点（/v3/api-docs.yaml/{group}）真实存在，
                // 与 OverrideIT 配对证明其 404 出自门禁而非 handler 缺失（裁定 D 修订）。
                "toolbox.docs.groups.admin.paths-to-match=/admin/**"
        })
@ActiveProfiles("prod")
class DocsExposureProductionIT {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void apiDocsIsBlockedInProduction() {
        assertThat(restTemplate.getForEntity("/v3/api-docs", String.class).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void apiDocsYamlVariantEndpointsAreBlockedInProduction() {
        // 裁定 D 修订：springdoc 另注册 {api-docs.path}.yaml（及分组 .yaml/{group}）端点，
        // 门禁 urlPatterns 必须一并覆盖，否则生产下 yaml 变体成泄漏面。
        assertThat(restTemplate.getForEntity("/v3/api-docs.yaml", String.class).getStatusCode().value())
                .isEqualTo(404);
        assertThat(restTemplate.getForEntity("/v3/api-docs.yaml/admin", String.class).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void swaggerUiIsBlockedInProduction() {
        assertThat(restTemplate.getForEntity("/swagger-ui/index.html", String.class).getStatusCode().value())
                .isEqualTo(404);
        assertThat(restTemplate.getForEntity("/swagger-ui.html", String.class).getStatusCode().value())
                .isEqualTo(404);
    }

    @Test
    void exportEndpointIsBlockedInProduction() {
        assertThat(restTemplate.getForEntity("/toolbox/docs/export?format=json", String.class)
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void businessEndpointsStayReachable() {
        // 门禁只覆盖文档相关路径，业务接口不受影响。
        assertThat(restTemplate.getForEntity("/api/items", String.class).getStatusCode().value()).isEqualTo(200);
    }
}
