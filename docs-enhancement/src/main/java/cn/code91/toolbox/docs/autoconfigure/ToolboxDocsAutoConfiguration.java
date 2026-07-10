package cn.code91.toolbox.docs.autoconfigure;

import cn.code91.toolbox.docs.core.DocsExporter;
import cn.code91.toolbox.docs.core.DocsExposureGate;
import cn.code91.toolbox.docs.export.DocsExportController;
import cn.code91.toolbox.docs.export.SpringdocDocsExporter;
import cn.code91.toolbox.docs.exposure.DefaultDocsExposureGate;
import cn.code91.toolbox.docs.exposure.DocsExposureFilter;
import cn.code91.toolbox.docs.schema.BaseResponseModelConverter;
import cn.code91.toolbox.docs.schema.ErrorShapeOpenApiCustomizer;
import cn.code91.toolbox.docs.schema.ErrorShapeOperationCustomizer;
import cn.code91.toolbox.docs.schema.PageBaseResponseModelConverter;
import cn.code91.toolbox.docs.schema.ResultLeakDetector;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.webmvc.api.MultipleOpenApiWebMvcResource;
import org.springdoc.webmvc.api.OpenApiWebMvcResource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.Locale;

/**
 * docs-enhancement 自动装配入口（06 设计文档 §5）。L1 以字符串形式探测 springdoc 核心类
 * （缺 springdoc 时本模块整体不装配，不影响应用启动——依赖全部 optional，见 06 §7）；
 * 模块 Servlet-only（06 §2.3 #4，对齐 facility 现有 Web 基建技术栈），故 web 条件收窄到
 * {@code Type.SERVLET}；L2 总开关默认 true（引依赖即表达使用意图）。
 *
 * <p>分组翻译不在此类：{@code DocsGroupsEnvironmentPostProcessor} 经
 * {@code META-INF/spring.factories} 注册（Boot 3 对 EnvironmentPostProcessor 仍只认
 * spring.factories，机制必需的唯一例外注册文件，见 06 §4.3），早于容器构建运行；
 * {@code AutoConfiguration.imports} 仍只注册本类这一个装配入口。
 *
 * <p>exposure/export 包的实现类不持有 {@link ToolboxDocsProperties} 类型（ArchUnit 规则 2：
 * autoconfigure 不被其它包依赖），由本类的 bean 方法抽取所需值下传构造器（06 §5 草图形态）。
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springdoc.core.models.GroupedOpenApi")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "toolbox.docs", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ToolboxDocsProperties.class)
public class ToolboxDocsAutoConfiguration {

    /**
     * Info/servers/security-schemes → {@link OpenAPI} bean（springdoc 以其为生成基底消费）。
     * 未配置的部分不写入（保持 springdoc 默认行为）；security-scheme type 非法在启动期
     * fail-fast（全仓原则 #8，消息含配置路径）。
     */
    @Bean
    @ConditionalOnMissingBean
    public OpenAPI toolboxDocsOpenApi(ToolboxDocsProperties properties) {
        OpenAPI openApi = new OpenAPI();
        if (!properties.info().isEmpty()) {
            openApi.setInfo(new Info()
                    .title(properties.info().title())
                    .version(properties.info().version())
                    .description(properties.info().description()));
        }
        if (!properties.servers().isEmpty()) {
            openApi.setServers(properties.servers().stream()
                    .map(server -> new Server().url(server.url()).description(server.description()))
                    .toList());
        }
        if (!properties.securitySchemes().isEmpty()) {
            Components components = new Components();
            properties.securitySchemes()
                    .forEach((name, scheme) -> components.addSecuritySchemes(name, toSecurityScheme(name, scheme)));
            openApi.setComponents(components);
        }
        return openApi;
    }

    @Bean
    @ConditionalOnMissingBean
    public DocsExposureGate defaultDocsExposureGate(ToolboxDocsProperties properties) {
        ToolboxDocsProperties.Exposure exposure = properties.exposure();
        return new DefaultDocsExposureGate(exposure.productionProfiles(), exposure.exposeInProduction());
    }

    /**
     * 请求期门禁过滤（06 §4.5，裁定 D）：urlPatterns 覆盖 api-docs（含分组子路径）、
     * swagger-ui 与导出端点；api-docs/swagger-ui 路径读 springdoc 同名属性——消费方改路径时
     * 门禁跟随，不硬编码默认值（设计风险 2）。
     */
    @Bean
    public FilterRegistrationBean<DocsExposureFilter> toolboxDocsExposureFilter(
            DocsExposureGate gate, Environment environment) {
        String apiDocsPath = environment.getProperty("springdoc.api-docs.path", "/v3/api-docs");
        String swaggerUiPath = environment.getProperty("springdoc.swagger-ui.path", "/swagger-ui.html");
        FilterRegistrationBean<DocsExposureFilter> registration =
                new FilterRegistrationBean<>(new DocsExposureFilter(gate, environment));
        // yaml 变体两条（裁定 D 修订）：springdoc 另注册 {api-docs.path}.yaml 与分组
        // .yaml/{group} 端点，Servlet 匹配规则下前两条 pattern 覆盖不到 .yaml 后缀路径，
        // 缺之生产环境 yaml 变体成泄漏面（IT 实测钉住）。
        registration.addUrlPatterns(
                apiDocsPath, apiDocsPath + "/*", apiDocsPath + ".yaml", apiDocsPath + ".yaml/*",
                swaggerUiPath, "/swagger-ui/*", DocsExportController.PATH);
        return registration;
    }

    /** 契约感知②③：springdoc 原生收集机制接入（ModelConverter/Customizer bean 即注册，约束 6 不自设 SPI）。 */
    @Bean
    public BaseResponseModelConverter baseResponseModelConverter() {
        return new BaseResponseModelConverter();
    }

    @Bean
    public PageBaseResponseModelConverter pageBaseResponseModelConverter() {
        return new PageBaseResponseModelConverter();
    }

    @Bean
    public ErrorShapeOpenApiCustomizer errorShapeOpenApiCustomizer() {
        return new ErrorShapeOpenApiCustomizer();
    }

    @Bean
    public ErrorShapeOperationCustomizer errorShapeOperationCustomizer() {
        return new ErrorShapeOperationCustomizer();
    }

    /** 契约感知①（裁定 C-1）：开关关闭时 bean 不注册（而非注册后空转）。 */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "toolbox.docs.contract-awareness", name = "result-leak-detection",
            havingValue = "true", matchIfMissing = true)
    public ResultLeakDetector resultLeakDetector(ObjectProvider<RequestMappingHandlerMapping> handlerMappings) {
        return new ResultLeakDetector(handlerMappings);
    }

    /**
     * 模块 i18n（00-overview §4.1）：注册模块自有 MessageSource 参与 facility
     * {@code AggregatedMessageSource}（{@code @Primary}）聚合。bundle 当前无模块自有键
     * （告警/错误消息暂为硬编码中文，迁移属后续独立决策），先落基础设施与
     * {@code toolbox.docs.} 键前缀纪律（同 storage/compare 样板）。
     */
    @Bean("toolboxDocsMessageSource")
    @ConditionalOnMissingBean(name = "toolboxDocsMessageSource")
    public ResourceBundleMessageSource toolboxDocsMessageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("i18n/toolbox-docs-messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(false);
        messageSource.setFallbackToSystemLocale(false);
        return messageSource;
    }

    /**
     * 导出能力（裁定 E）：{@code toolbox.docs.export.enabled=false} 时 controller 与 exporter
     * 均不注册。组名合法性的事实来源 = {@code toolbox.docs.groups} 键集，抽取后下传。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "toolbox.docs.export", name = "enabled", havingValue = "true",
            matchIfMissing = true)
    static class ExportConfiguration {

        @Bean
        @ConditionalOnMissingBean
        DocsExporter defaultDocsExporter(ObjectProvider<OpenApiWebMvcResource> defaultDocument,
                                         ObjectProvider<MultipleOpenApiWebMvcResource> groupedDocuments,
                                         ToolboxDocsProperties properties) {
            return new SpringdocDocsExporter(defaultDocument, groupedDocuments, properties.groups().keySet());
        }

        @Bean
        DocsExportController docsExportController(DocsExporter exporter) {
            return new DocsExportController(exporter);
        }
    }

    private static SecurityScheme toSecurityScheme(String name, ToolboxDocsProperties.SecurityScheme config) {
        SecurityScheme scheme = new SecurityScheme();
        try {
            scheme.setType(SecurityScheme.Type.valueOf(config.type().toUpperCase(Locale.ROOT)));
        } catch (RuntimeException ex) { // type 缺失（NPE）或非法值（IAE）
            throw new IllegalStateException("toolbox.docs.security-schemes." + name + ".type 非法："
                    + config.type() + "（可选值：http/apiKey/oauth2/openIdConnect/mutualTLS）", ex);
        }
        scheme.setScheme(config.scheme());
        scheme.setBearerFormat(config.bearerFormat());
        return scheme;
    }
}
