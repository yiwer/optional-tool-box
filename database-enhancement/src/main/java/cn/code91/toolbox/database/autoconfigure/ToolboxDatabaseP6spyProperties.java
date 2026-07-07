package cn.code91.toolbox.database.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code toolbox.database.p6spy.*} 配置。
 *
 * @param enabled p6spy DataSource 代理是否启用；默认 {@code false}（有运行时开销，属调试型能力，
 *                见总体设计 §3.2 默认值哲学）。p6spy 自身的 appender/format/filter 等细节走
 *                classpath 上的 {@code spy.properties} 文件，不由本配置类管理。
 */
@ConfigurationProperties(prefix = "toolbox.database.p6spy")
public record ToolboxDatabaseP6spyProperties(boolean enabled) {
}
