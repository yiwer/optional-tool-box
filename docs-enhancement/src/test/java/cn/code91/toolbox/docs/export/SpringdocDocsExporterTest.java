package cn.code91.toolbox.docs.export;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.docs.core.DocsError;
import cn.code91.toolbox.docs.core.ExportFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springdoc.webmvc.api.MultipleOpenApiWebMvcResource;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * springdoc 底座导出（裁定 E）：组名合法性以配置组名集合为事实来源；springdoc 资源 bean
 * 缺失/抛错一律收敛为 {@code Err(ExportFailed)} 不外抛；{@code HttpServletRequest} 经
 * {@code RequestContextHolder} 获取（模块 Servlet-only）。
 */
class SpringdocDocsExporterTest {

    private static final byte[] SPEC_JSON =
            "{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"t\"},\"paths\":{}}".getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void bindRequestContext() {
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest("GET", "/toolbox/docs/export")));
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void unknownGroupFailsWithConfiguredGroupListGuidance() {
        SpringdocDocsExporter exporter = new SpringdocDocsExporter(
                providerOf(), providerOf(), List.of("admin", "open"));

        Result<byte[], DocsError> result = exporter.export("nope", ExportFormat.JSON);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr().message()).contains("nope").contains("admin").contains("open");
    }

    @Test
    void blankGroupMeansDefaultDocument() throws Exception {
        OpenApiWebMvcResource resource = Mockito.mock(OpenApiWebMvcResource.class);
        Mockito.when(resource.openapiJson(any(), anyString(), any())).thenReturn(SPEC_JSON);
        SpringdocDocsExporter exporter = new SpringdocDocsExporter(
                providerOf(resource), providerOf(), List.of());

        assertThat(exporter.export(null, ExportFormat.JSON).get()).isEqualTo(SPEC_JSON);
        assertThat(exporter.export("   ", ExportFormat.JSON).get()).isEqualTo(SPEC_JSON);
    }

    @Test
    void yamlDelegatesToSpringdocYamlEndpointMethod() throws Exception {
        byte[] yaml = "openapi: 3.0.1".getBytes(StandardCharsets.UTF_8);
        OpenApiWebMvcResource resource = Mockito.mock(OpenApiWebMvcResource.class);
        Mockito.when(resource.openapiYaml(any(), anyString(), any())).thenReturn(yaml);
        SpringdocDocsExporter exporter = new SpringdocDocsExporter(
                providerOf(resource), providerOf(), List.of());

        assertThat(exporter.export(null, ExportFormat.YAML).get()).isEqualTo(yaml);
    }

    @Test
    void postmanConvertsDefaultDocumentJsonSpec() throws Exception {
        OpenApiWebMvcResource resource = Mockito.mock(OpenApiWebMvcResource.class);
        Mockito.when(resource.openapiJson(any(), anyString(), any())).thenReturn(SPEC_JSON);
        SpringdocDocsExporter exporter = new SpringdocDocsExporter(
                providerOf(resource), providerOf(), List.of());

        Result<byte[], DocsError> result = exporter.export(null, ExportFormat.POSTMAN);

        assertThat(result.isOk()).isTrue();
        assertThat(new String(result.get(), StandardCharsets.UTF_8))
                .contains("https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
    }

    @Test
    void knownGroupDelegatesToGroupedSpringdocResource() throws Exception {
        MultipleOpenApiWebMvcResource resource = Mockito.mock(MultipleOpenApiWebMvcResource.class);
        Mockito.when(resource.openapiJson(any(), anyString(), eq("admin"), any())).thenReturn(SPEC_JSON);
        SpringdocDocsExporter exporter = new SpringdocDocsExporter(
                providerOf(), providerOf(resource), List.of("admin"));

        assertThat(exporter.export("admin", ExportFormat.JSON).get()).isEqualTo(SPEC_JSON);
    }

    @Test
    void springdocResourceExceptionConvergesToExportFailed() throws Exception {
        OpenApiWebMvcResource resource = Mockito.mock(OpenApiWebMvcResource.class);
        Mockito.when(resource.openapiJson(any(), anyString(), any()))
                .thenThrow(new IllegalStateException("spec 生成失败"));
        SpringdocDocsExporter exporter = new SpringdocDocsExporter(
                providerOf(resource), providerOf(), List.of());

        Result<byte[], DocsError> result = exporter.export(null, ExportFormat.JSON);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr().message()).contains("spec 生成失败");
    }

    @Test
    void missingSpringdocResourceBeanConvergesToExportFailed() {
        SpringdocDocsExporter exporter = new SpringdocDocsExporter(providerOf(), providerOf(), List.of("admin"));

        assertThat(exporter.export(null, ExportFormat.JSON).isErr()).isTrue();
        assertThat(exporter.export("admin", ExportFormat.JSON).isErr()).isTrue();
    }

    @Test
    void missingRequestContextConvergesToExportFailed() {
        RequestContextHolder.resetRequestAttributes();
        OpenApiWebMvcResource resource = Mockito.mock(OpenApiWebMvcResource.class);
        SpringdocDocsExporter exporter = new SpringdocDocsExporter(providerOf(resource), providerOf(), List.of());

        Result<byte[], DocsError> result = exporter.export(null, ExportFormat.JSON);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr().message()).contains("请求上下文");
    }

    @SafeVarargs
    private static <T> ObjectProvider<T> providerOf(T... instances) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new UnsupportedOperationException("测试桩只支持 getIfAvailable()");
            }

            @Override
            public T getIfAvailable() {
                return instances.length == 0 ? null : instances[0];
            }
        };
    }
}
