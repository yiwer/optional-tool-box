package cn.code91.toolbox.docs.export;

import cn.code91.facility.log.LogUtil;
import cn.code91.facility.result.Result;
import cn.code91.toolbox.docs.core.DocsError;
import cn.code91.toolbox.docs.core.DocsExporter;
import cn.code91.toolbox.docs.core.ExportFailed;
import cn.code91.toolbox.docs.core.ExportFormat;
import jakarta.servlet.http.HttpServletRequest;
import org.springdoc.webmvc.api.MultipleOpenApiWebMvcResource;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * springdoc 底座导出（裁定 E）：JSON/YAML 直接复用 springdoc 资源 bean 的公开端点方法
 * （不重造 spec 生成器），POSTMAN 在 JSON 基础上经 {@link PostmanCollectionWriter} 转换。
 * 组名合法性以配置组名集合为事实来源（装配层自 {@code toolbox.docs.groups} 键集抽取下传，
 * ArchUnit 规则 2：本包不依赖 autoconfigure 类型）；springdoc 内部抛错一律捕获收敛为
 * {@code Err(ExportFailed)}，不外抛。
 */
public class SpringdocDocsExporter implements DocsExporter {

    private final ObjectProvider<OpenApiWebMvcResource> defaultDocument;
    private final ObjectProvider<MultipleOpenApiWebMvcResource> groupedDocuments;
    private final Set<String> configuredGroups;
    private final PostmanCollectionWriter postmanWriter = new PostmanCollectionWriter();

    public SpringdocDocsExporter(ObjectProvider<OpenApiWebMvcResource> defaultDocument,
                                 ObjectProvider<MultipleOpenApiWebMvcResource> groupedDocuments,
                                 Collection<String> configuredGroupNames) {
        this.defaultDocument = defaultDocument;
        this.groupedDocuments = groupedDocuments;
        this.configuredGroups = Set.copyOf(configuredGroupNames);
    }

    @Override
    public Result<byte[], DocsError> export(String group, ExportFormat format) {
        String normalized = (group == null || group.isBlank()) ? null : group.trim();
        if (normalized != null && !configuredGroups.contains(normalized)) {
            return Result.err(new ExportFailed("未知文档分组：" + normalized
                    + "，已配置分组：" + configuredGroupsForMessage()));
        }
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return Result.err(new ExportFailed(
                    "导出需在 HTTP 请求上下文内调用（模块 Servlet-only，当前请求经 RequestContextHolder 获取）"));
        }
        try {
            byte[] spec = fetchSpec(normalized, format, request);
            if (spec == null) {
                return Result.err(new ExportFailed("springdoc 文档资源 bean 不可用，无法导出"
                        + (normalized == null ? "" : "分组 " + normalized)));
            }
            return format == ExportFormat.POSTMAN ? postmanWriter.toCollection(spec) : Result.ok(spec);
        } catch (Exception ex) {
            // 收敛不外抛（裁定 E）；单行告警不带栈——失败原因已随 Err 返回调用方。
            LogUtil.warn("OpenAPI spec 导出失败（group={}，format={}）：{}", normalized, format, ex.toString());
            return Result.err(new ExportFailed("OpenAPI spec 导出失败：" + ex.getMessage()));
        }
    }

    private byte[] fetchSpec(String group, ExportFormat format, HttpServletRequest request) throws Exception {
        Locale locale = LocaleContextHolder.getLocale();
        if (group == null) {
            OpenApiWebMvcResource resource = defaultDocument.getIfAvailable();
            if (resource == null) {
                return null;
            }
            return format == ExportFormat.YAML
                    ? resource.openapiYaml(request, request.getRequestURI(), locale)
                    : resource.openapiJson(request, request.getRequestURI(), locale);
        }
        MultipleOpenApiWebMvcResource resource = groupedDocuments.getIfAvailable();
        if (resource == null) {
            return null;
        }
        String apiDocsUrl = groupAdjustedApiDocsUrl(request.getRequestURI(), group);
        return format == ExportFormat.YAML
                ? resource.openapiYaml(request, apiDocsUrl, group, locale)
                : resource.openapiJson(request, apiDocsUrl, group, locale);
    }

    /**
     * springdoc 的分组资源会先把 {@code "/" + group} 拼到 apiDocsUrl 再按长度从请求 URL 尾部
     * 截出 server base URL（实测 2.8.17 {@code MultipleOpenApiResource}/{@code getServerUrl}）；
     * 为使长度算术在"经导出端点调用"时依然成立，这里预先按同样长度收缩请求 URI。
     * 该值只参与长度截取，不参与路由。
     */
    private static String groupAdjustedApiDocsUrl(String requestUri, String group) {
        int trimmed = group.length() + 1;
        return requestUri.length() > trimmed ? requestUri.substring(0, requestUri.length() - trimmed) : "";
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes servletAttributes
                ? servletAttributes.getRequest()
                : null;
    }

    private String configuredGroupsForMessage() {
        return configuredGroups.isEmpty() ? "（无）" : String.join("、", new TreeSet<>(configuredGroups));
    }
}
