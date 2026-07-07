package cn.code91.toolbox.llm.prompt;

import cn.code91.facility.context.SpringContextHolder;
import cn.code91.toolbox.llm.core.LlmError;
import cn.code91.toolbox.llm.spi.PromptTemplateLoader;
import cn.code91.facility.result.Result;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词模板静态门面（03 §4.3），委托 {@link PromptTemplateLoader}（容器中的 bean，
 * 缺容器/缺 bean 时降级为默认 {@link ClasspathPromptTemplateLoader} 实例——同 facility
 * {@code HttpClients}/{@code MaskUtil} 的静态门面 + 容器可覆盖资源解析模式，保证本类无 Spring
 * 容器也可独立使用）。
 *
 * <p><b>占位符语义（测试钉住，同 mail {@code SimpleTemplateRenderer} 先例）</b>：
 * {@code ${var}} 中 vars 含键 → 替换为 {@code String.valueOf(value)}（null 值替换为空串）；
 * vars 不含键 → 占位符<b>原样保留</b>（缺失变量在渲染结果里直接可见，便于联调期发现漏传，
 * 静默清空会把缺陷藏进空白，与 mail 模板渲染器语义保持一致）。</p>
 *
 * <p><b>缺模板错误型复用说明</b>：{@link LlmError} 未单列模板相关错误型（sealed 8 型已在
 * 裁定 A 交付物清单中固定），缺模板复用 {@link LlmError.ProviderError}——语义上最贴近
 * "未归类的基础设施级失败"，{@code providerCode} 固定为 {@code "template-not-found"}
 * 便于调用方按 code 区分，而不是新增第 9 个 sealed 分支。</p>
 */
public final class PromptTemplates {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}]+)}");
    private static final PromptTemplateLoader DEFAULT_LOADER = new ClasspathPromptTemplateLoader();

    private PromptTemplates() {
        throw new UnsupportedOperationException();
    }

    /**
     * 解析容器中的 {@link PromptTemplateLoader} bean；不存在时降级为默认 classpath 实现。
     */
    private static PromptTemplateLoader loader() {
        return SpringContextHolder.getBean(PromptTemplateLoader.class).orElse(DEFAULT_LOADER);
    }

    /**
     * 渲染模板：加载原文后替换 {@code ${var}} 占位符。
     *
     * @param name 模板逻辑名
     * @param vars 变量表（可为 null，等价空表）
     * @return 渲染结果；模板不存在时 {@code Err(ProviderError)}（见类 Javadoc 复用说明）
     */
    public static Result<String, LlmError> render(String name, Map<String, Object> vars) {
        Optional<String> template = loader().load(name);
        if (template.isEmpty()) {
            return Result.err(new LlmError.ProviderError(
                    "提示词模板不存在：classpath:prompts/" + name + ".md"
                            + "（或自定义 PromptTemplateLoader 未覆盖该名）；请创建该模板文件", "template-not-found"));
        }
        return Result.ok(substitute(template.get(), vars == null ? Map.of() : vars));
    }

    private static String substitute(String template, Map<String, Object> vars) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder(template.length());
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement;
            if (!vars.containsKey(key)) {
                // 缺失变量原样保留（类 Javadoc 语义），需转义 $ 防止被 appendReplacement 解析。
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
