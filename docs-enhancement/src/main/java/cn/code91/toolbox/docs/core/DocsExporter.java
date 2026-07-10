package cn.code91.toolbox.docs.core;

import cn.code91.facility.result.Result;

/**
 * OpenAPI spec 导出 Seam（06 §4.1，L4：默认实现挂 {@code @ConditionalOnMissingBean}，
 * 应用可整体替换）。
 */
public interface DocsExporter {

    /**
     * 导出指定分组的 spec。
     *
     * @param group  分组名；null/空白 = 默认文档（未配置分组时的单一 spec）；
     *               未知组名 → {@code Err(ExportFailed)}，消息含已配置组名列表引导
     * @param format 目标格式
     * @return 成功时为对应格式的字节流
     */
    Result<byte[], DocsError> export(String group, ExportFormat format);
}
