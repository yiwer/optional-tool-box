package cn.code91.toolbox.docs.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code toolbox.docs.*} 配置（06 设计文档 §6）。
 *
 * @param enabled           模块总开关（实际装配与否由 {@link ToolboxDocsAutoConfiguration} 类上的
 *                          {@code @ConditionalOnProperty(matchIfMissing = true)} 决定，本字段仅供
 *                          配置元数据展示，同 storage 先例）
 * @param info              文档基础信息（title/version/description），仅配置了才写入 OpenAPI bean
 * @param servers           文档展示的服务器列表
 * @param securitySchemes   安全方案（仅文档展示，不代为鉴权，06 §2.2）；键为方案名（如 bearerAuth）
 * @param groups            具名分组；由 {@code DocsGroupsEnvironmentPostProcessor} 在容器构建前独立
 *                          Binder 绑定并翻译为 springdoc 原生属性，此处同形声明供配置元数据与
 *                          导出组名校验（{@code groups().keySet()} 是组名合法性的事实来源，裁定 E）
 * @param exposure          环境门禁参数
 * @param export            导出能力开关（bean 注册与否由 {@code @ConditionalOnProperty} 决定，
 *                          本字段仅供配置元数据展示）
 * @param contractAwareness 契约感知开关（同上）
 */
@ConfigurationProperties(prefix = "toolbox.docs")
public record ToolboxDocsProperties(
        boolean enabled,
        @NestedConfigurationProperty Info info,
        List<Server> servers,
        Map<String, SecurityScheme> securitySchemes,
        Map<String, Group> groups,
        @NestedConfigurationProperty Exposure exposure,
        @NestedConfigurationProperty Export export,
        @NestedConfigurationProperty ContractAwareness contractAwareness) {

    public ToolboxDocsProperties {
        info = info == null ? Info.empty() : info;
        servers = servers == null ? List.of() : List.copyOf(servers);
        securitySchemes = securitySchemes == null ? Map.of() : copyPreservingOrder(securitySchemes);
        groups = groups == null ? Map.of() : copyPreservingOrder(groups);
        exposure = exposure == null ? Exposure.defaults() : exposure;
        export = export == null ? Export.defaults() : export;
        contractAwareness = contractAwareness == null ? ContractAwareness.defaults() : contractAwareness;
    }

    /** {@code Map.copyOf} 不保序；文档配置面保持声明序（分组序影响 springdoc 展示序）。 */
    private static <V> Map<String, V> copyPreservingOrder(Map<String, V> source) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * 文档基础信息（06 §6）。
     */
    public record Info(String title, String version, String description) {

        static Info empty() {
            return new Info(null, null, null);
        }

        boolean isEmpty() {
            return title == null && version == null && description == null;
        }
    }

    /**
     * 文档展示的服务器条目。
     */
    public record Server(String url, String description) {
    }

    /**
     * 安全方案（仅文档展示）。
     *
     * @param type         方案类型：http/apiKey/oauth2/openIdConnect/mutualTLS（大小写不敏感）
     * @param scheme       http 类型下的 scheme（如 bearer）
     * @param bearerFormat bearer 场景的格式说明（如 JWT）
     */
    public record SecurityScheme(String type, String scheme, String bearerFormat) {
    }

    /**
     * 具名分组的选择器（裁定 B）。与 {@code DocsGroupsEnvironmentPostProcessor.Group} 同形
     * 不复用：EPP 在容器构建前运行且 ArchUnit 规则 2 禁止其依赖本包。
     */
    public record Group(List<String> packagesToScan, List<String> pathsToMatch, List<String> pathsToExclude) {

        public Group {
            packagesToScan = packagesToScan == null ? List.of() : List.copyOf(packagesToScan);
            pathsToMatch = pathsToMatch == null ? List.of() : List.copyOf(pathsToMatch);
            pathsToExclude = pathsToExclude == null ? List.of() : List.copyOf(pathsToExclude);
        }
    }

    /**
     * 环境门禁参数（裁定 D）。
     *
     * @param productionProfiles  视为生产环境的 profile 集（默认 {@code [prod, production]}）
     * @param exposeInProduction  是否在生产 profile 下仍暴露文档（默认 false，需显式打开）
     */
    public record Exposure(List<String> productionProfiles, boolean exposeInProduction) {

        public Exposure {
            productionProfiles = productionProfiles == null
                    ? List.of("prod", "production") : List.copyOf(productionProfiles);
        }

        static Exposure defaults() {
            return new Exposure(null, false);
        }
    }

    /**
     * 导出能力开关（默认开；生产环境下依附 exposure 判定同样不可用）。
     */
    public record Export(boolean enabled) {

        static Export defaults() {
            return new Export(true);
        }
    }

    /**
     * 契约感知开关。
     *
     * @param resultLeakDetection Result 通道泄漏检测（默认开，裁定 C-1）
     */
    public record ContractAwareness(boolean resultLeakDetection) {

        static ContractAwareness defaults() {
            return new ContractAwareness(true);
        }
    }
}
