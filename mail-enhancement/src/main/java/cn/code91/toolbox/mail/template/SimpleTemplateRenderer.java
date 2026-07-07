package cn.code91.toolbox.mail.template;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.mail.core.MailError;
import cn.code91.toolbox.mail.spi.MailTemplateRenderer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 轻量 {@code ${var}} 模板渲染器（P1 默认实现）。模板<b>只查</b>
 * {@code classpath:mail-templates/{name}.html}（裁定 A：多语言回退属 P2，
 * {@code locale} 参数仅为 API 稳定性保留，本实现忽略之）。
 *
 * <p>占位符语义（测试钉住）：</p>
 * <ul>
 *   <li>vars 含键且值非 null → 替换为 {@code String.valueOf(value)}；</li>
 *   <li>vars 含键但值为 null → 替换为空串（显式给 null 视为"就是要清空"）；</li>
 *   <li>vars 不含键 → <b>占位符原样保留</b>——缺失变量在收到的邮件里直接可见，
 *       便于联调期发现漏传，而静默空串会把缺陷藏进空白。</li>
 * </ul>
 */
public final class SimpleTemplateRenderer implements MailTemplateRenderer {

    @Override
    public String name() {
        return "simple";
    }

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");

    @Override
    public Result<String, MailError> render(String templateName, Map<String, Object> vars, Locale locale) {
        if (templateName == null || templateName.isBlank()) {
            return Result.err(new MailError.TemplateError("模板名为空，无法定位 classpath:mail-templates/ 下的模板文件"));
        }
        String location = "mail-templates/" + templateName + ".html";
        String template;
        try (InputStream stream = SimpleTemplateRenderer.class.getClassLoader().getResourceAsStream(location)) {
            if (stream == null) {
                return Result.err(new MailError.TemplateError(
                        "模板不存在：classpath:" + location + "；请在该路径下创建模板文件（P1 无多语言回退，仅查此一处）"));
            }
            template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return Result.err(new MailError.TemplateError(
                    "读取模板 classpath:" + location + " 失败：" + e.getMessage()));
        }
        return Result.ok(substitute(template, vars == null ? Map.of() : vars));
    }

    private static String substitute(String template, Map<String, Object> vars) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder(template.length());
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement;
            if (!vars.containsKey(key)) {
                // 缺失变量原样保留（见类 Javadoc 语义），需转义 $ 防止被 appendReplacement 解析。
                replacement = matcher.group();
            } else {
                Object value = vars.get(key);
                replacement = value == null ? "" : String.valueOf(value);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
