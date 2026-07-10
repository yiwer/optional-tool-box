package cn.code91.toolbox.auth.autoconfigure;

import cn.code91.facility.log.LogUtil;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 运行期 fail-open 探测器（07 §5.5，裁定 R6/R7）：L1 缺引擎类的退出态是 fail-open——依赖
 * 重构误 exclude starter 时应用静默裸奔（README 警示段）。本装配与主装配的
 * {@code @ConditionalOnClass} 恰成反相：引擎类缺失时它恰好活着，检测到 {@code toolbox.auth.*}
 * 配置在场即打 WARN 单条（含行动指引）；{@code enabled=false} 显式退出静默（R7，复用 L2
 * 条件语义）；非 servlet web 应用无裸奔面，不警。独立注册于 AutoConfiguration.imports
 * 第二行（R6：否决 EnvironmentPostProcessor / ApplicationListener，见 §2.4）。
 */
@AutoConfiguration
@ConditionalOnMissingClass("org.springframework.security.oauth2.jwt.JwtDecoder")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "toolbox.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ToolboxAuthFailOpenDetector {

    /** 探测结果（07 §5.5）：{@code presentKeys} 非空时已发出 WARN；装配矩阵借在场性断言探测器激活。 */
    public record FailOpenWarning(List<String> presentKeys) {
    }

    @Bean
    public FailOpenWarning toolboxAuthFailOpenWarning(Environment environment) {
        List<String> keys = presentAuthKeys(environment);
        if (!keys.isEmpty()) {
            LogUtil.warn("auth-enhancement 检测到 toolbox.auth.* 配置在场（{}）但鉴权引擎类缺失"
                    + "（spring-security-oauth2-jose 不在 classpath）——模块零装配，应用当前无任何"
                    + "鉴权保护（fail-open）。请引入 spring-boot-starter-oauth2-resource-server；"
                    + "确认弃用则设 toolbox.auth.enabled=false（07 §5.5）。", keys);
        }
        return new FailOpenWarning(keys);
    }

    /**
     * 扫全部可枚举 PropertySource 收集 {@code toolbox.auth.} 前缀键；环境变量形态经 Boot 松绑
     * 呈现为 {@code TOOLBOX_AUTH_} 前缀（如 k8s 注入），一并识别。TreeSet 排序去重，WARN 输出稳定。
     */
    private static List<String> presentAuthKeys(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return List.of();
        }
        Set<String> keys = new TreeSet<>();
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (name.startsWith("toolbox.auth.") || name.startsWith("TOOLBOX_AUTH_")) {
                        keys.add(name);
                    }
                }
            }
        }
        return List.copyOf(keys);
    }
}
