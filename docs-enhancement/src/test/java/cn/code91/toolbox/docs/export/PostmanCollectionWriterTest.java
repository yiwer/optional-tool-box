package cn.code91.toolbox.docs.export;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.docs.core.DocsError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Postman collection v2.1 转换（裁定 E）：以 JSON spec 为输入（Jackson tree），
 * item 逐 {@code paths.<path>.<method>} 产出；name 三级兜底 summary → operationId →
 * {@code "METHOD path"}；P1 最小可用（无 auth/body/示例，README 披露）。
 */
class PostmanCollectionWriterTest {

    private static final String FIXTURE = """
            {
              "openapi": "3.0.1",
              "info": { "title": "示例服务 API", "version": "1.0.0" },
              "paths": {
                "/admin/things": {
                  "parameters": [ { "name": "traceId", "in": "header" } ],
                  "get": { "summary": "列出事项", "operationId": "listThings" }
                },
                "/api/items": {
                  "post": { "operationId": "createItem" }
                },
                "/misc": {
                  "delete": { }
                }
              }
            }
            """;

    private final PostmanCollectionWriter writer = new PostmanCollectionWriter();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void buildsCollectionSkeletonWithInfoNameFromSpecTitleAndPinnedSchemaUrl() throws Exception {
        JsonNode collection = convert(FIXTURE);

        assertThat(collection.path("info").path("name").asText()).isEqualTo("示例服务 API");
        assertThat(collection.path("info").path("schema").asText())
                .isEqualTo("https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
    }

    @Test
    void emitsOneItemPerPathAndHttpMethodIgnoringNonMethodKeys() throws Exception {
        JsonNode items = convert(FIXTURE).path("item");

        // path 级 parameters/servers 等非 HTTP 方法键不产出 item。
        assertThat(items.size()).isEqualTo(3);
    }

    @Test
    void itemNameFallsBackFromSummaryToOperationIdToMethodAndPath() throws Exception {
        JsonNode items = convert(FIXTURE).path("item");

        assertThat(nameOf(items, "/admin/things")).isEqualTo("列出事项");
        assertThat(nameOf(items, "/api/items")).isEqualTo("createItem");
        assertThat(nameOf(items, "/misc")).isEqualTo("DELETE /misc");
    }

    @Test
    void requestCarriesUppercasedMethodAndBaseUrlPlaceholderUrl() throws Exception {
        JsonNode items = convert(FIXTURE).path("item");
        JsonNode request = itemFor(items, "/admin/things").path("request");

        assertThat(request.path("method").asText()).isEqualTo("GET");
        assertThat(request.path("url").path("raw").asText()).isEqualTo("{{baseUrl}}/admin/things");
        assertThat(request.path("url").path("host").get(0).asText()).isEqualTo("{{baseUrl}}");
        assertThat(request.path("url").path("path").get(0).asText()).isEqualTo("admin");
        assertThat(request.path("url").path("path").get(1).asText()).isEqualTo("things");
        assertThat(request.path("url").path("path").size()).isEqualTo(2);
        // P1 最小可用：不含 auth/body。
        assertThat(request.has("auth")).isFalse();
        assertThat(request.has("body")).isFalse();
    }

    @Test
    void blankSpecTitleFallsBackToApi() throws Exception {
        JsonNode collection = convert("""
                { "openapi": "3.0.1", "paths": { "/x": { "get": { } } } }
                """);

        assertThat(collection.path("info").path("name").asText()).isEqualTo("API");
    }

    @Test
    void invalidJsonConvergesToExportFailed() {
        Result<byte[], DocsError> result = writer.toCollection("not-json{".getBytes(StandardCharsets.UTF_8));

        assertThat(result.isErr()).isTrue();
    }

    private JsonNode convert(String openApiJson) throws Exception {
        Result<byte[], DocsError> result = writer.toCollection(openApiJson.getBytes(StandardCharsets.UTF_8));
        assertThat(result.isOk()).as(() -> "转换应成功：" + result).isTrue();
        return mapper.readTree(result.get());
    }

    private static String nameOf(JsonNode items, String path) {
        return itemFor(items, path).path("name").asText();
    }

    private static JsonNode itemFor(JsonNode items, String path) {
        for (JsonNode item : items) {
            if (item.path("request").path("url").path("raw").asText().equals("{{baseUrl}}" + path)) {
                return item;
            }
        }
        throw new AssertionError("collection 中不存在 path 对应 item：" + path);
    }
}
