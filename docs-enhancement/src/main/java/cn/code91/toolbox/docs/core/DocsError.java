package cn.code91.toolbox.docs.core;

/**
 * docs 模块显式错误通道（06 §4.2，与其余模块同构：只有 {@code message()}，无数字 code）。
 *
 * <p>P1 仅保留唯一真实流经 {@code Result} 的变体 {@link ExportFailed}：分组配置错误按全仓
 * 原则 #8 在启动期抛 {@code IllegalStateException} fail-fast（不经 Result 流动）；schema
 * 命名修饰失败只记日志并退回默认命名（不产生错误值）。永不流经 API 的变体不入 sealed 集
 * （全局约束 3——不得加回 {@code InvalidGroupConfig}/{@code SchemaResolutionFailed} 死桩）。
 */
public sealed interface DocsError permits ExportFailed {

    String message();
}
