package cn.code91.toolbox.docs.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;

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
 */
@AutoConfiguration
@ConditionalOnClass(name = "org.springdoc.core.models.GroupedOpenApi")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "toolbox.docs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ToolboxDocsAutoConfiguration {
}
