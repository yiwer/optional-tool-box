package cn.code91.toolbox.database.autoconfigure;

import cn.code91.toolbox.database.dialect.PostgresDialect;
import cn.code91.toolbox.database.dialect.SqlDialect;
import cn.code91.toolbox.database.naming.ColumnNamingStrategy;
import cn.code91.toolbox.database.observability.P6spyDataSourceWrappingBeanPostProcessor;
import cn.code91.toolbox.database.registry.FieldHandlerRegistry;
import cn.code91.toolbox.database.registry.FieldHandlerRegistryCustomizer;
import cn.code91.toolbox.database.spi.FieldHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * database-enhancement 自动装配入口（02 设计文档 §5）。L1（{@code @ConditionalOnClass}
 * 探测 {@link NamedParameterJdbcTemplate}）+ L2（模块总开关，默认开）+ 四个 L4 Seam bean
 * （{@link ColumnNamingStrategy}/{@link SqlDialect}/{@link FieldHandlerRegistry} 均
 * {@code @ConditionalOnMissingBean}，应用可整体覆盖）+ p6spy BPP（L1+L2 双重条件，默认关，
 * 见类文档"要点"）。
 *
 * <p>本类只做 Task 7（类型系统底座 + 观测）的装配；Task 8 的 L1/L2/L3（
 * {@code AnnotatedParameterSource}/{@code SqlBuilder}/{@code EntityRowMapper}/
 * {@code JdbcRepository}）均非单例 bean，不在本类装配（与 beacon 一致：由消费方按实体类型
 * 自行构造）。</p>
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(NamedParameterJdbcTemplate.class)
@ConditionalOnProperty(prefix = "toolbox.database", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties({ToolboxDatabaseProperties.class, ToolboxDatabaseP6spyProperties.class})
public class ToolboxDatabaseAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ColumnNamingStrategy columnNamingStrategy() {
        return ColumnNamingStrategy.CAMEL_TO_UNDERSCORE;
    }

    /**
     * P1 唯一实现固定为 {@link PostgresDialect}（{@link ToolboxDatabaseProperties#dialect()}
     * 目前仅作只读展示，见其 Javadoc；P4 加 MySQL 方言时才会成为真正的选型分支）。
     */
    @Bean
    @ConditionalOnMissingBean
    public SqlDialect sqlDialect() {
        return new PostgresDialect();
    }

    /**
     * 三索引 handler 注册表：{@code dialect.registerBuiltins} 先注册方言特化 handler，
     * 再 {@code registerBuiltins} 补标准 handler，随后收集应用暴露的
     * {@code FieldHandler<?>} bean 与 {@link FieldHandlerRegistryCustomizer} bean，
     * 最终 {@code freeze()}（运行期不可变，见 {@link FieldHandlerRegistry} 类文档）。
     *
     * <p>顺序说明：方言 handler 先于标准 handler 注册，是为了让"alias 不反盖"规则在两者
     * 有 javaType 冲突时保持确定性——不过 P1 的标准 handler 与 PG 特化 handler 覆盖的
     * javaType 集合刻意不重叠（如 uuid/timestamptz 走 sqlType 精确路由，不占用
     * {@code byJavaType} 通用 slot），故先后顺序在当前内置集下不影响可观察行为，
     * 但作为未来扩展的契约仍显式声明。</p>
     */
    @Bean
    @ConditionalOnMissingBean
    public FieldHandlerRegistry fieldHandlerRegistry(
            SqlDialect dialect,
            ObjectProvider<FieldHandler<?>> handlers,
            ObjectProvider<FieldHandlerRegistryCustomizer> customizers) {
        FieldHandlerRegistry registry = new FieldHandlerRegistry();
        dialect.registerBuiltins(registry);
        FieldHandlerRegistry.registerBuiltins(registry);
        handlers.orderedStream().forEach(registry::register);
        customizers.orderedStream().forEach(c -> c.customize(registry));
        registry.freeze();
        return registry;
    }

    /**
     * p6spy {@link javax.sql.DataSource} 包装 BPP。
     * <p>要点：{@code static @Bean}（避免过早初始化宿主 {@code ToolboxDatabaseAutoConfiguration}
     * 配置类本身）；{@code @ConditionalOnProperty(havingValue = "true")}（无 matchIfMissing，
     * 默认关闭——p6spy 有运行时开销，属调试型能力，见总体设计 §3.2 默认值哲学）；
     * {@code @ConditionalOnClass(name = "...")} 用字符串形式探测可选依赖，消费方未引入
     * p6spy jar 时本 bean 直接不装配（不报错）。{@code @AutoConfiguration(after =
     * DataSourceAutoConfiguration.class)} 保证 {@code DataSource} bean 在本 BPP
     * 注册前已就绪，故能被正常包装。</p>
     */
    @Bean
    @ConditionalOnProperty(prefix = "toolbox.database.p6spy", name = "enabled", havingValue = "true")
    @ConditionalOnClass(name = "com.p6spy.engine.spy.P6DataSource")
    public static BeanPostProcessor toolboxDatabaseP6spyDataSourceWrapper() {
        return new P6spyDataSourceWrappingBeanPostProcessor();
    }
}
