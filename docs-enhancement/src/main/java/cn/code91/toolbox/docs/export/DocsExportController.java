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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

    /**
     * 参数姿势两处实测因果：① {@code @RequestParam} 显式写参数名——本仓库编译不开
     * {@code -parameters}，库 jar 不依赖编译器旗标恢复形参名，缺名时参数解析直接 IAE；
     * ② {@code format} 收 String 手工解析——Spring MVC 对枚举请求参数的默认转换大小写敏感
     * （Boot 的宽松枚举转换只作用于配置绑定，不作用于 MVC），{@code format=yaml} 会直接
     * 绑定失败；手工解析给出大小写不敏感语义与确定的 400 + {@code BaseResponse} 形态
     * （裁定 E，集成测试钉住）。
     */
    @GetMapping(PATH)
    public ResponseEntity<?> export(@RequestParam(name = "group", required = false) String group,
                                    @RequestParam(name = "format", defaultValue = "json") String format) {
        ExportFormat exportFormat = parseFormat(format);
        if (exportFormat == null) {
            return ResponseEntity.badRequest().body(BaseResponse.err(400,
                    "非法的 format 参数值：" + format + "（仅支持 json/yaml/postman，大小写不敏感）"));
        }
        Result<byte[], DocsError> result = exporter.export(group, exportFormat);
        return switch (result) {
            case Result.Ok<byte[], DocsError>(byte[] bytes) -> ResponseEntity.ok()
                    .contentType(contentTypeOf(exportFormat))
                    .header(HttpHeaders.CONTENT_DISPOSITION, attachmentOf(group, exportFormat))
                    .body(bytes);
            case Result.Err<byte[], DocsError>(ExportFailed(String message)) ->
                    ResponseEntity.status(HttpStatus.NOT_FOUND).body(BaseResponse.err(404, message));
        };
    }

    private static ExportFormat parseFormat(String format) {
        try {
            return ExportFormat.valueOf(format.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
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
