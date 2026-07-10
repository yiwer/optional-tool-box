package cn.code91.toolbox.docs.grouping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分组配置面翻译层（06 §4.3，裁定 B）：把 {@code toolbox.docs.groups.<name>.*} 翻译为 springdoc
 * 原生的 {@code springdoc.group-configs[i].*}——springdoc 原生已支持属性驱动多分组（配套
 * AnyNestedCondition 兜住属性路径），本类不重新实现分组机制。不走 {@code GroupedOpenApi}
 * bean 注册：springdoc 多分组装配由 {@code @ConditionalOnBean(GroupedOpenApi.class)} 守卫，
 * 其求值早于任何本模块 {@code @Bean} 声明的 BFPP 能注册分组 bean 的时机（06 §4.3 修正）。
 *
 * <p>经 {@code META-INF/spring.factories} 注册（EPP 不是装配类，Boot 3 对 EPP 仍只认
 * spring.factories——全局约束 2 的唯一例外注册文件）。本类只引用 Spring 类型，消费方未引入
 * springdoc 时仍安全运行（翻译产物无人消费，无副作用）。
 *
 * <p>产物 property source 以 {@code addLast} 挂载：消费方显式写的 springdoc 原生属性优先
 * （README 声明两种配置面不要混用）。组名即 map 键，天然唯一，无重名校验。
 */
public class DocsGroupsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "toolboxDocsGroups";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (!environment.getProperty("toolbox.docs.enabled", Boolean.class, true)) {
            return;
        }
        Map<String, Group> groups = Binder.get(environment)
                .bind("toolbox.docs.groups", Bindable.mapOf(String.class, Group.class))
                .orElseGet(Map::of);
        if (groups.isEmpty()) {
            return;
        }
        Map<String, Object> translated = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, Group> entry : groups.entrySet()) {
            translate(entry.getKey(), entry.getValue(), index++, translated);
        }
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, translated));
    }

    /**
     * 必须晚于 {@code ConfigDataEnvironmentPostProcessor}（加载消费方 application.yml）运行，
     * 否则 Binder 看不到文件配置源里的分组声明；取最低优先级显式钉住该时序约束。
     */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private static void translate(String name, Group group, int index, Map<String, Object> out) {
        if (group.hasNoSelector()) {
            throw new IllegalStateException("toolbox.docs.groups." + name
                    + " 三个选择器（packages-to-scan/paths-to-match/paths-to-exclude）均为空，"
                    + "至少配置 packages-to-scan 或 paths-to-match 之一");
        }
        String prefix = "springdoc.group-configs[" + index + "].";
        out.put(prefix + "group", name);
        expand(out, prefix + "packages-to-scan", group.packagesToScan());
        expand(out, prefix + "paths-to-match", group.pathsToMatch());
        expand(out, prefix + "paths-to-exclude", group.pathsToExclude());
    }

    private static void expand(Map<String, Object> out, String key, List<String> values) {
        for (int j = 0; j < values.size(); j++) {
            out.put(key + "[" + j + "]", values.get(j));
        }
    }

    /**
     * 供 {@link Binder} 绑定的分组形状（裁定 B）。与 autoconfigure 的
     * {@code ToolboxDocsProperties.Group} 同形但不复用：本类在容器构建前运行，且 ArchUnit
     * 规则 2 禁止 grouping 包依赖 autoconfigure 包（配置元数据与运行期校验由后者承担）。
     */
    public record Group(List<String> packagesToScan, List<String> pathsToMatch, List<String> pathsToExclude) {

        public Group {
            packagesToScan = packagesToScan == null ? List.of() : List.copyOf(packagesToScan);
            pathsToMatch = pathsToMatch == null ? List.of() : List.copyOf(pathsToMatch);
            pathsToExclude = pathsToExclude == null ? List.of() : List.copyOf(pathsToExclude);
        }

        boolean hasNoSelector() {
            return packagesToScan.isEmpty() && pathsToMatch.isEmpty() && pathsToExclude.isEmpty();
        }
    }
}
