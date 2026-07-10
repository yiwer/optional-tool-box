package cn.code91.toolbox.docs.core;

/**
 * 导出失败（06 §4.2）：分组不存在、springdoc spec 生成失败或格式转换失败。
 *
 * @param message 失败原因；分组不存在时含已配置组名列表引导
 */
public record ExportFailed(String message) implements DocsError {
}
