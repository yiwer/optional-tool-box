package cn.code91.toolbox.mail.spi;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.mail.core.MailError;

import java.util.Locale;
import java.util.Map;

/**
 * 模板渲染 Seam（L4：默认轻量 {@code ${var}} 实现，Thymeleaf adapter 属 P2，裁定 A）。
 */
public interface MailTemplateRenderer {

    /**
     * 渲染器标识（如 {@code simple}）。
     */
    String name();

    /**
     * 渲染模板为 HTML 正文。
     *
     * @param locale 目标语言。<b>P1 仅为 API 稳定性保留</b>（裁定 A：多语言模板回退属 P2，
     *               P1 默认实现忽略该参数），实现不得因 null locale 失败
     */
    Result<String, MailError> render(String templateName, Map<String, Object> vars, Locale locale);
}
