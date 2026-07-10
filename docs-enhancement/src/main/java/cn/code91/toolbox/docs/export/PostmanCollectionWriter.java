package cn.code91.toolbox.docs.export;

import cn.code91.facility.json.JsonUtil;
import cn.code91.toolbox.docs.core.DocsError;
import cn.code91.toolbox.docs.core.ExportFailed;
import cn.code91.facility.result.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OpenAPI JSON spec → Postman collection v2.1（裁定 E）：以 springdoc 已生成的 JSON 为输入
 * （Jackson tree，序列化/反序列化复用 facility {@code JsonUtil}，不自建——全局约束 6），
 * 每 {@code paths.<path>.<method>} 产出一个 item。P1 最小可用：不含 auth/body/示例
 * （README 披露）。
 */
public class PostmanCollectionWriter {

    private static final String SCHEMA_URL = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json";

    /** OpenAPI path item 里除 HTTP 方法外还有 parameters/servers/summary 等结构键，需过滤。 */
    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    public Result<byte[], DocsError> toCollection(byte[] openApiJson) {
        Result<JsonNode, ?> parsed = JsonUtil.parseTree(new String(openApiJson, StandardCharsets.UTF_8));
        if (parsed.isErr()) {
            return Result.err(new ExportFailed("OpenAPI spec JSON 解析失败，无法转换 Postman collection"));
        }
        JsonNode spec = parsed.get();

        ObjectNode collection = JsonNodeFactory.instance.objectNode();
        ObjectNode info = collection.putObject("info");
        String title = spec.path("info").path("title").asText("");
        info.put("name", title.isBlank() ? "API" : title);
        info.put("schema", SCHEMA_URL);

        ArrayNode items = collection.putArray("item");
        for (Map.Entry<String, JsonNode> pathEntry : spec.path("paths").properties()) {
            for (Map.Entry<String, JsonNode> methodEntry : pathEntry.getValue().properties()) {
                String method = methodEntry.getKey().toLowerCase(Locale.ROOT);
                if (HTTP_METHODS.contains(method)) {
                    items.add(item(pathEntry.getKey(), method, methodEntry.getValue()));
                }
            }
        }

        return JsonUtil.serializeToBytes(collection)
                .mapErr(error -> new ExportFailed("Postman collection 序列化失败"));
    }

    private static ObjectNode item(String path, String method, JsonNode operation) {
        ObjectNode item = JsonNodeFactory.instance.objectNode();
        item.put("name", itemName(path, method, operation));

        ObjectNode request = item.putObject("request");
        request.put("method", method.toUpperCase(Locale.ROOT));
        ObjectNode url = request.putObject("url");
        url.put("raw", "{{baseUrl}}" + path);
        url.putArray("host").add("{{baseUrl}}");
        ArrayNode segments = url.putArray("path");
        for (String segment : path.split("/")) {
            if (!segment.isBlank()) {
                segments.add(segment);
            }
        }
        return item;
    }

    /** name 三级兜底：operation summary → operationId → {@code "METHOD path"}（裁定 E）。 */
    private static String itemName(String path, String method, JsonNode operation) {
        String summary = operation.path("summary").asText("");
        if (!summary.isBlank()) {
            return summary;
        }
        String operationId = operation.path("operationId").asText("");
        if (!operationId.isBlank()) {
            return operationId;
        }
        return method.toUpperCase(Locale.ROOT) + " " + path;
    }
}
