package cn.code91.toolbox.docs.autoconfigure;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.docs.core.DocsError;
import cn.code91.toolbox.docs.core.DocsExporter;
import cn.code91.toolbox.docs.core.DocsExposureGate;
import cn.code91.toolbox.docs.core.ExportFormat;
import cn.code91.toolbox.docs.export.DocsExportController;
import cn.code91.toolbox.docs.export.SpringdocDocsExporter;
import cn.code91.toolbox.docs.exposure.DefaultDocsExposureGate;
import cn.code91.toolbox.docs.exposure.DocsExposureFilter;
import cn.code91.toolbox.docs.schema.BaseResponseModelConverter;
import cn.code91.toolbox.docs.schema.ErrorShapeOpenApiCustomizer;
import cn.code91.toolbox.docs.schema.ErrorShapeOperationCustomizer;
import cn.code91.toolbox.docs.schema.PageBaseResponseModelConverter;
import cn.code91.toolbox.docs.schema.ResultLeakDetector;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 装配矩阵（06 §9）：默认全 bean 齐；缺 springdoc 类整体不装配；enabled=false 不装配；
 * 子开关（泄漏检测/导出）独立关停；用户覆盖 {@code OpenAPI}/{@code DocsExposureGate}/
 * {@code DocsExporter} 让位（L4 Seam）；门禁 urlPatterns 读 springdoc 路径属性不硬编码。
 * 模块 Servlet-only，矩阵以 {@link WebApplicationContextRunner} 驱动。
 */
class ToolboxDocsAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolboxDocsAutoConfiguration.class));

    @Test
    void defaultAssemblyRegistersAllBeans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(OpenAPI.class);
            assertThat(context).hasSingleBean(DocsExposureGate.class);
            assertThat(context.getBean(DocsExposureGate.class)).isInstanceOf(DefaultDocsExposureGate.class);
            assertThat(context).hasSingleBean(DocsExporter.class);
            assertThat(context.getBean(DocsExporter.class)).isInstanceOf(SpringdocDocsExporter.class);
            // PageBaseResponseModelConverter 继承 BaseResponseModelConverter，按 bean 名断言二者各自在场。
            assertThat(context).hasBean("baseResponseModelConverter");
            assertThat(context).hasSingleBean(PageBaseResponseModelConverter.class);
            assertThat(context).hasSingleBean(ErrorShapeOpenApiCustomizer.class);
            assertThat(context).hasSingleBean(ErrorShapeOperationCustomizer.class);
            assertThat(context).hasSingleBean(ResultLeakDetector.class);
            assertThat(context).hasSingleBean(DocsExportController.class);
            assertThat(context).hasSingleBean(FilterRegistrationBean.class);
            assertThat(context).hasBean("toolboxDocsMessageSource");
        });
    }

    @Test
    void missingSpringdocClassDisablesWholeModule() {
        contextRunner
                .withClassLoader(new FilteredClassLoader("org.springdoc.core.models.GroupedOpenApi"))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DocsExposureGate.class);
                    assertThat(context).doesNotHaveBean(DocsExporter.class);
                    assertThat(context).doesNotHaveBean("toolboxDocsMessageSource");
                });
    }

    @Test
    void enabledFalseAssemblesNothing() {
        contextRunner.withPropertyValues("toolbox.docs.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(DocsExposureGate.class);
            assertThat(context).doesNotHaveBean(DocsExporter.class);
            assertThat(context).doesNotHaveBean(ResultLeakDetector.class);
        });
    }

    @Test
    void resultLeakDetectionCanBeSwitchedOffIndependently() {
        contextRunner.withPropertyValues("toolbox.docs.contract-awareness.result-leak-detection=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ResultLeakDetector.class);
                    // 其余能力不受影响。
                    assertThat(context).hasSingleBean(DocsExposureGate.class);
                    assertThat(context).hasSingleBean(DocsExporter.class);
                });
    }

    @Test
    void exportDisabledRemovesExporterAndController() {
        contextRunner.withPropertyValues("toolbox.docs.export.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(DocsExporter.class);
            assertThat(context).doesNotHaveBean(DocsExportController.class);
            assertThat(context).hasSingleBean(DocsExposureGate.class);
            assertThat(context).hasSingleBean(ResultLeakDetector.class);
        });
    }

    @Test
    void userDefinedExposureGateTakesPrecedence() {
        contextRunner.withUserConfiguration(CustomGateConfig.class).run(context -> {
            assertThat(context).hasSingleBean(DocsExposureGate.class);
            assertThat(context.getBean(DocsExposureGate.class)).isInstanceOf(CustomGateConfig.StubGate.class);
        });
    }

    @Test
    void userDefinedExporterTakesPrecedence() {
        contextRunner.withUserConfiguration(CustomExporterConfig.class).run(context -> {
            assertThat(context).hasSingleBean(DocsExporter.class);
            assertThat(context.getBean(DocsExporter.class)).isInstanceOf(CustomExporterConfig.StubExporter.class);
        });
    }

    @Test
    void userDefinedOpenApiTakesPrecedence() {
        contextRunner.withUserConfiguration(CustomOpenApiConfig.class).run(context -> {
            assertThat(context).hasSingleBean(OpenAPI.class);
            assertThat(context.getBean(OpenAPI.class).getInfo().getTitle()).isEqualTo("用户自定义");
        });
    }

    @Test
    void openApiBeanCarriesInfoServersAndSecuritySchemesFromProperties() {
        contextRunner
                .withPropertyValues(
                        "toolbox.docs.info.title=示例服务 API",
                        "toolbox.docs.info.version=1.2.3",
                        "toolbox.docs.info.description=演示描述",
                        "toolbox.docs.servers[0].url=https://api.example.com",
                        "toolbox.docs.servers[0].description=生产",
                        "toolbox.docs.servers[1].url=http://localhost:8080",
                        "toolbox.docs.security-schemes.bearerAuth.type=http",
                        "toolbox.docs.security-schemes.bearerAuth.scheme=bearer",
                        "toolbox.docs.security-schemes.bearerAuth.bearer-format=JWT")
                .run(context -> {
                    OpenAPI openApi = context.getBean(OpenAPI.class);
                    assertThat(openApi.getInfo().getTitle()).isEqualTo("示例服务 API");
                    assertThat(openApi.getInfo().getVersion()).isEqualTo("1.2.3");
                    assertThat(openApi.getInfo().getDescription()).isEqualTo("演示描述");
                    assertThat(openApi.getServers()).hasSize(2);
                    assertThat(openApi.getServers().get(0).getUrl()).isEqualTo("https://api.example.com");
                    assertThat(openApi.getServers().get(0).getDescription()).isEqualTo("生产");

                    SecurityScheme scheme = openApi.getComponents().getSecuritySchemes().get("bearerAuth");
                    assertThat(scheme.getType()).isEqualTo(SecurityScheme.Type.HTTP);
                    assertThat(scheme.getScheme()).isEqualTo("bearer");
                    assertThat(scheme.getBearerFormat()).isEqualTo("JWT");
                });
    }

    @Test
    void unconfiguredOpenApiBeanStaysBare() {
        contextRunner.run(context -> {
            OpenAPI openApi = context.getBean(OpenAPI.class);
            assertThat(openApi.getInfo()).isNull();
            assertThat(openApi.getServers()).isNull();
            assertThat(openApi.getComponents()).isNull();
        });
    }

    @Test
    void illegalSecuritySchemeTypeFailsFastWithConfigPath() {
        contextRunner
                .withPropertyValues("toolbox.docs.security-schemes.bad.type=nonsense")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // 配置路径引导位于异常链中段（root cause 是枚举 IAE），按整链断言。
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("toolbox.docs.security-schemes.bad.type");
                });
    }

    @Test
    void exposureFilterCoversSpringdocDefaultPathsAndExportEndpoint() {
        contextRunner.run(context -> {
            FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
            assertThat(registration.getFilter()).isInstanceOf(DocsExposureFilter.class);
            assertThat(registration.getUrlPatterns()).containsExactlyInAnyOrder(
                    "/v3/api-docs", "/v3/api-docs/*", "/swagger-ui.html", "/swagger-ui/*", "/toolbox/docs/export");
        });
    }

    @Test
    void exposureFilterFollowsCustomizedSpringdocPathProperties() {
        // 设计风险 2：消费方改了 springdoc 路径属性时门禁必须跟随（读属性不硬编码）。
        contextRunner
                .withPropertyValues(
                        "springdoc.api-docs.path=/custom/docs",
                        "springdoc.swagger-ui.path=/custom/ui.html")
                .run(context -> {
                    FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
                    assertThat(registration.getUrlPatterns()).containsExactlyInAnyOrder(
                            "/custom/docs", "/custom/docs/*", "/custom/ui.html", "/swagger-ui/*",
                            "/toolbox/docs/export");
                });
    }

    @Configuration
    static class CustomGateConfig {
        @Bean
        DocsExposureGate docsExposureGate() {
            return new StubGate();
        }

        static final class StubGate implements DocsExposureGate {
            @Override
            public boolean isExposed(org.springframework.core.env.Environment env) {
                return true;
            }
        }
    }

    @Configuration
    static class CustomExporterConfig {
        @Bean
        DocsExporter docsExporter() {
            return new StubExporter();
        }

        static final class StubExporter implements DocsExporter {
            @Override
            public Result<byte[], DocsError> export(String group, ExportFormat format) {
                return Result.ok(new byte[0]);
            }
        }
    }

    @Configuration
    static class CustomOpenApiConfig {
        @Bean
        OpenAPI openApi() {
            return new OpenAPI().info(new io.swagger.v3.oas.models.info.Info().title("用户自定义"));
        }
    }
}
