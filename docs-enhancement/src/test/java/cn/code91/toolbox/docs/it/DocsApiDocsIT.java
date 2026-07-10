package cn.code91.toolbox.docs.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端真链路（06 §9，本仓库首个内嵌 Web 容器测试）：EnvironmentPostProcessor 分组翻译 →
 * springdoc 原生多分组 → 契约感知 schema/错误形状 → 三格式导出，全部走真实 HTTP。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = DocsItApplication.class,
        properties = {
                "spring.main.banner-mode=off",
                "toolbox.docs.info.title=集成测试 API",
                "toolbox.docs.groups.admin.paths-to-match=/admin/**",
                "toolbox.docs.groups.open.paths-to-match=/api/**"
        })
@ExtendWith(OutputCaptureExtension.class)
class DocsApiDocsIT {

    @Autowired
    private TestRestTemplate restTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void defaultApiDocsCarriesDecoratedSchemasAndGenericErrorShape() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode spec = mapper.readTree(response.getBody());

        JsonNode schemas = spec.path("components").path("schemas");
        assertThat(schemas.has("TestDtoResponse")).as("BaseResponse<TestDto> 应修饰为 TestDtoResponse").isTrue();
        assertThat(schemas.has("TestDtoPageResponse"))
                .as("PageBaseResponse<TestDto> 应修饰为 TestDtoPageResponse").isTrue();
        assertThat(schemas.has("ToolboxErrorResponse")).as("通用错误形状 schema 应注册").isTrue();
        // data: TestDto 泛型解析正确（06 §1 澄清：springdoc 本就能解析，模块只改名不改结构）。
        assertThat(schemas.path("TestDtoResponse").path("properties").path("data").path("$ref").asText())
                .contains("TestDto");

        JsonNode defaultResponse = spec.path("paths").path("/admin/things").path("get")
                .path("responses").path("default");
        assertThat(defaultResponse.path("description").asText()).isEqualTo("统一错误响应（BaseResponse 错误分支通用形状）");
        assertThat(defaultResponse.path("content").path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/ToolboxErrorResponse");
    }

    @Test
    void groupedApiDocsContainsOnlyGroupPaths() throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs/admin", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode paths = mapper.readTree(response.getBody()).path("paths");
        assertThat(paths.has("/admin/things")).isTrue();
        assertThat(paths.has("/api/items")).as("admin 组不应包含 open 组路径").isFalse();
    }

    @Test
    void yamlExportReturnsYamlAttachment() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/toolbox/docs/export?format=yaml", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).contains("openapi:");
        assertThat(response.getHeaders().getContentType().toString()).contains("yaml");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment").contains("openapi.yaml");
        // format 大小写不敏感（控制器手工解析语义）。
        assertThat(restTemplate.getForEntity("/toolbox/docs/export?format=YAML", String.class)
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void postmanExportReturnsValidCollection() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/toolbox/docs/export?format=postman", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode collection = mapper.readTree(response.getBody());
        assertThat(collection.path("info").path("name").asText()).isEqualTo("集成测试 API");
        assertThat(collection.path("info").path("schema").asText())
                .isEqualTo("https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
        assertThat(collection.path("item").size()).isGreaterThanOrEqualTo(3);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("openapi.postman_collection.json");
    }

    @Test
    void groupExportGoesThroughRealSpringdocGroupResource() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/toolbox/docs/export?group=admin&format=json", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        JsonNode paths = mapper.readTree(response.getBody()).path("paths");
        assertThat(paths.has("/admin/things")).isTrue();
        assertThat(paths.has("/api/items")).isFalse();
    }

    @Test
    void unknownGroupExportIs404WithBaseResponseErrorShape() throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/toolbox/docs/export?group=nope", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.path("code").asInt()).isEqualTo(404);
        assertThat(body.path("message").asText()).contains("nope").contains("admin").contains("open");
        assertThat(body.has("data")).isTrue();
        assertThat(body.has("description")).isTrue();
    }

    @Test
    void illegalFormatValueIs400WithBaseResponseErrorShape() throws Exception {
        // 裁定 E：枚举绑定失败经控制器级 @ExceptionHandler 收敛，此测试钉住实际形态。
        ResponseEntity<String> response =
                restTemplate.getForEntity("/toolbox/docs/export?format=bogus", String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        JsonNode body = mapper.readTree(response.getBody());
        assertThat(body.path("code").asInt()).isEqualTo(400);
        assertThat(body.path("message").asText()).contains("bogus").contains("json/yaml/postman");
    }

    @Test
    void leakWarningAppearsAtStartupWithoutBlockingBoot(CapturedOutput output) {
        // 应用已正常启动（本测试能收到任意 HTTP 响应即为证）；启动日志含对故意泄漏者的告警。
        assertThat(restTemplate.getForEntity("/api/items", String.class).getStatusCode().value()).isEqualTo(200);
        assertThat(output).contains("LeakyTestController#leak");
        assertThat(output).contains("BaseResponse.fromResult");
    }
}
