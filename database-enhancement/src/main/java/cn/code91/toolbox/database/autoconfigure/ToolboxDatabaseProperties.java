package cn.code91.toolbox.database.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code toolbox.database.*} 配置（02 设计文档 §6）。
 *
 * @param enabled       模块总开关（实际装配与否由 {@link ToolboxDatabaseAutoConfiguration} 类上的
 *                      {@code @ConditionalOnProperty(matchIfMissing = true)} 决定，本字段仅供配置
 *                      元数据展示，同 compare/storage/mail 先例）
 * @param dialect       方言只读展示；P1 唯一取值 "postgresql"（{@code SqlDialect} 装配固定为
 *                      {@code PostgresDialect}，本字段不驱动方言选型逻辑——P4 加 MySQL 方言时
 *                      才会成为真正的选型开关）
 * @param namingStrategy 命名策略只读展示；P1 唯一装配值 "camel-to-underscore"
 */
@ConfigurationProperties(prefix = "toolbox.database")
public record ToolboxDatabaseProperties(boolean enabled, String dialect, String namingStrategy) {

    public ToolboxDatabaseProperties {
        if (dialect == null || dialect.isBlank()) {
            dialect = "postgresql";
        }
        if (namingStrategy == null || namingStrategy.isBlank()) {
            namingStrategy = "camel-to-underscore";
        }
    }
}
