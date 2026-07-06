package cn.code91.toolbox.compare.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * {@code toolbox.compare.*} 配置（01 设计文档 §6）。
 *
 * @param enabled     模块总开关。注意：实际"装配与否"由 {@link ToolboxCompareAutoConfiguration} 类上的
 *                    {@code @ConditionalOnProperty(matchIfMissing = true)} 决定（未配置时默认按 true 装配）；
 *                    本字段仅随 {@code @ConfigurationProperties} 常规绑定，供配置元数据展示，不作为二次判断依据
 *                    （boolean 原生类型无法在本记录的紧凑构造器里区分"未配置"与"显式 false"，故不在此重复默认值修正）。
 * @param maxDepth    对象图递归深度上限（默认 8）
 * @param nullAsEmpty null 与空串是否视为相等（默认 false）
 * @param datePattern 日期时间默认渲染格式
 * @param render      PlainTextRenderer 三模板配置
 */
@ConfigurationProperties(prefix = "toolbox.compare")
public record ToolboxCompareProperties(
        boolean enabled,
        int maxDepth,
        boolean nullAsEmpty,
        String datePattern,
        @NestedConfigurationProperty Render render) {

    private static final String DEFAULT_DATE_PATTERN = "yyyy-MM-dd HH:mm:ss";

    public ToolboxCompareProperties {
        if (maxDepth <= 0) {
            maxDepth = 8;
        }
        if (datePattern == null || datePattern.isBlank()) {
            datePattern = DEFAULT_DATE_PATTERN;
        }
        if (render == null) {
            render = Render.defaults();
        }
    }

    /**
     * PlainTextRenderer 三模板配置，占位符 {@code {label}/{old}/{new}}。
     */
    public record Render(String modifiedTemplate, String addedTemplate, String removedTemplate) {

        public Render {
            if (modifiedTemplate == null || modifiedTemplate.isBlank()) {
                modifiedTemplate = "{label}：由「{old}」改为「{new}」";
            }
            if (addedTemplate == null || addedTemplate.isBlank()) {
                addedTemplate = "{label}：新增「{new}」";
            }
            if (removedTemplate == null || removedTemplate.isBlank()) {
                removedTemplate = "{label}：移除「{old}」";
            }
        }

        public static Render defaults() {
            return new Render("{label}：由「{old}」改为「{new}」", "{label}：新增「{new}」", "{label}：移除「{old}」");
        }
    }
}
