package cn.code91.toolbox.docs.export;

import cn.code91.facility.result.Result;
import cn.code91.facility.web.response.BaseResponse;
import cn.code91.toolbox.docs.core.DocsError;
import cn.code91.toolbox.docs.core.DocsExporter;
import cn.code91.toolbox.docs.core.ExportFailed;
import cn.code91.toolbox.docs.core.ExportFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.nio.charset.StandardCharsets;

/**
 * 导出 HTTP 端点（裁定 E）：{@code GET /toolbox/docs/export?group=&format=}（format 默认 json）。
 * 端点自身不做门禁判定——统一交给 {@code DocsExposureFilter}（06 §5，urlPatterns 覆盖本路径）。
 */
@RestController
public class DocsExportController {

    /** 导出端点路径；装配层的门禁 FilterRegistrationBean 据此计算 urlPatterns（单一事实来源）。 */
    public static final String PATH = "/toolbox/docs/export";

    private final DocsExporter exporter;

    public DocsExportController(DocsExporter exporter) {
        this.exporter = exporter;
    }

    @GetMapping(PATH)
    public ResponseEntity<?> export(@RequestParam(required = false) String group,
                                    @RequestParam(defaultValue = "json") ExportFormat format) {
        Result<byte[], DocsError> result = exporter.export(group, format);
        return switch (result) {
            case Result.Ok<byte[], DocsError>(byte[] bytes) -> ResponseEntity.ok()
                    .contentType(contentTypeOf(format))
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachmentOf(group, format))
                    .body(bytes);
            case Result.Err<byte[], DocsError>(ExportFailed(String message)) ->
                    ResponseEntity.status(HttpStatus.NOT_FOUND).body(BaseResponse.err(404, message));
        };
    }

    /**
     * {@code format} 非法值时 Spring 枚举绑定失败抛本异常；收敛为 400 + {@code BaseResponse}
     * 错误形状（裁定 E，集成测试钉住实际形态）。{@code @ExceptionHandler} 只作用于本控制器，
     * 不影响应用全局异常处理。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<BaseResponse<Void>> onBindingFailure(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.badRequest().body(BaseResponse.err(400,
                "非法的请求参数值：" + ex.getValue() + "（format 仅支持 json/yaml/postman）"));
    }

    private static MediaType contentTypeOf(ExportFormat format) {
        // postman collection 本体就是 JSON。
        return format == ExportFormat.YAML ? MediaType.APPLICATION_YAML : MediaType.APPLICATION_JSON;
    }

    private static String attachmentOf(String group, ExportFormat format) {
        String base = (group == null || group.isBlank()) ? "openapi" : "openapi-" + group.trim();
        String filename = base + switch (format) {
            case JSON -> ".json";
            case YAML -> ".yaml";
            case POSTMAN -> ".postman_collection.json";
        };
        return ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString();
    }
}
