package cn.code91.toolbox.compare.render;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.compare.core.ChangeKind;
import cn.code91.toolbox.compare.core.CompareError;
import cn.code91.toolbox.compare.core.DiffResult;
import cn.code91.toolbox.compare.core.FieldChange;
import cn.code91.toolbox.compare.spi.ChangeRenderer;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 纯文本渲染器：按变更类型选用三种可配模板，占位符 {@code {label}/{old}/{new}}。
 */
public final class PlainTextRenderer implements ChangeRenderer {

    private static final String DEFAULT_MODIFIED_TEMPLATE = "{label}：由「{old}」改为「{new}」";
    private static final String DEFAULT_ADDED_TEMPLATE = "{label}：新增「{new}」";
    private static final String DEFAULT_REMOVED_TEMPLATE = "{label}：移除「{old}」";

    private final String modifiedTemplate;
    private final String addedTemplate;
    private final String removedTemplate;

    public PlainTextRenderer(String modifiedTemplate, String addedTemplate, String removedTemplate) {
        this.modifiedTemplate = Objects.requireNonNull(modifiedTemplate, "modifiedTemplate cannot be null");
        this.addedTemplate = Objects.requireNonNull(addedTemplate, "addedTemplate cannot be null");
        this.removedTemplate = Objects.requireNonNull(removedTemplate, "removedTemplate cannot be null");
    }

    public static PlainTextRenderer withDefaultTemplates() {
        return new PlainTextRenderer(DEFAULT_MODIFIED_TEMPLATE, DEFAULT_ADDED_TEMPLATE, DEFAULT_REMOVED_TEMPLATE);
    }

    @Override
    public Result<String, CompareError> render(DiffResult result) {
        Objects.requireNonNull(result, "result cannot be null");
        String text = result.changes().stream()
                .map(this::renderOne)
                .collect(Collectors.joining(System.lineSeparator()));
        return Result.ok(text);
    }

    private String renderOne(FieldChange change) {
        String template = templateFor(change.kind());
        return template
                .replace("{label}", nullToEmpty(change.label()))
                .replace("{old}", nullToEmpty(change.oldText()))
                .replace("{new}", nullToEmpty(change.newText()));
    }

    private String templateFor(ChangeKind kind) {
        return switch (kind) {
            case MODIFIED -> modifiedTemplate;
            case ADDED -> addedTemplate;
            case REMOVED -> removedTemplate;
        };
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
