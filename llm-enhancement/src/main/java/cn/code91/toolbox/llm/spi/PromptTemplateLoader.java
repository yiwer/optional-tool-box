package cn.code91.toolbox.llm.spi;

import java.util.Optional;

/**
 * 提示词模板加载 Seam（L4：默认 classpath 实现，03 §4.3）。模板平台化（热更新/灰度）
 * 超出本模块定位，不做——本接口只负责"按名取模板原始文本"这一层。
 */
public interface PromptTemplateLoader {

    /**
     * 加载模板原始文本（含 {@code ${var}} 占位符，未渲染）。
     *
     * @param name 模板逻辑名（不含扩展名与目录前缀，如 {@code ticket-extract}）
     * @return 模板文本；不存在时为空
     */
    Optional<String> load(String name);
}
