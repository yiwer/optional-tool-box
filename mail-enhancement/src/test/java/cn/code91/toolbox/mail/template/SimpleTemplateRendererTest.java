package cn.code91.toolbox.mail.template;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.mail.core.MailError;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SimpleTemplateRenderer}（brief 测试矩阵）：正常替换 / 缺模板 → TemplateError 含路径 /
 * 缺变量语义（不含键→原样保留；含键值 null→空串，实现者裁量并在此钉住）。
 */
class SimpleTemplateRendererTest {

    private final SimpleTemplateRenderer renderer = new SimpleTemplateRenderer();

    @Test
    void nameIsSimple() {
        assertThat(renderer.name()).isEqualTo("simple");
    }

    @Test
    void rendersTemplateWithVariableSubstitution() {
        Result<String, MailError> result = renderer.render(
                "welcome", Map.of("name", "张三", "code", 246810, "remark", "尽快使用"), Locale.SIMPLIFIED_CHINESE);

        assertThat(result.isOk()).isTrue();
        String html = result.get();
        assertThat(html).contains("你好，张三！");
        // 同一变量出现多次应全部替换
        assertThat(html).contains("你的验证码是 246810，编号 246810 十分钟内有效。");
        assertThat(html).doesNotContain("${");
    }

    @Test
    void localeIsIgnoredInP1AndBaseTemplateIsAlwaysUsed() {
        // 裁定 A：P1 无多语言回退，任何 locale（含 null）都只查 mail-templates/{name}.html。
        Result<String, MailError> withLocale = renderer.render(
                "welcome", Map.of("name", "A", "code", 1, "remark", "r"), Locale.US);
        Result<String, MailError> withoutLocale = renderer.render(
                "welcome", Map.of("name", "A", "code", 1, "remark", "r"), null);

        assertThat(withLocale.isOk()).isTrue();
        assertThat(withoutLocale.isOk()).isTrue();
        assertThat(withLocale.get()).isEqualTo(withoutLocale.get());
    }

    @Test
    void missingTemplateReturnsTemplateErrorWithClasspathLocation() {
        Result<String, MailError> result = renderer.render("no-such-template", Map.of(), null);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.TemplateError.class);
        assertThat(result.getErr().message()).contains("classpath:mail-templates/no-such-template.html");
    }

    @Test
    void absentVariableKeepsPlaceholderAsIs() {
        // 缺变量语义钉住：vars 不含 remark 键 → ${remark} 原样保留（联调期直接可见）。
        Result<String, MailError> result = renderer.render(
                "welcome", Map.of("name", "张三", "code", 1), null);

        assertThat(result.isOk()).isTrue();
        assertThat(result.get()).contains("备注：${remark}");
    }

    @Test
    void nullValuedVariableRendersAsEmptyString() {
        // 缺变量语义钉住：vars 含键但值为 null → 空串（显式 null 视为"就是要清空"）。
        Map<String, Object> vars = new HashMap<>();
        vars.put("name", "张三");
        vars.put("code", 1);
        vars.put("remark", null);

        Result<String, MailError> result = renderer.render("welcome", vars, null);

        assertThat(result.isOk()).isTrue();
        assertThat(result.get()).contains("备注：</p>");
        assertThat(result.get()).doesNotContain("${remark}");
    }

    @Test
    void nullVarsBehavesAsEmptyVars() {
        Result<String, MailError> result = renderer.render("welcome", null, null);

        assertThat(result.isOk()).isTrue();
        assertThat(result.get()).contains("${name}");
    }

    @Test
    void nullTemplateNameReturnsTemplateError() {
        Result<String, MailError> result = renderer.render(null, Map.of(), null);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(MailError.TemplateError.class);
    }
}
