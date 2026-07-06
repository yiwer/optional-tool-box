package cn.code91.toolbox.compare.render;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.compare.core.ChangeKind;
import cn.code91.toolbox.compare.core.CompareError;
import cn.code91.toolbox.compare.core.DiffResult;
import cn.code91.toolbox.compare.core.FieldChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 三模板占位符替换：{@code {label}/{old}/{new}}。
 */
class PlainTextRendererTest {

    @Test
    void defaultTemplatesRenderModifiedAddedRemoved() {
        PlainTextRenderer renderer = PlainTextRenderer.withDefaultTemplates();
        DiffResult diff = new DiffResult(List.of(
                new FieldChange("amount", "订单金额", ChangeKind.MODIFIED, null, null, "100", "120"),
                new FieldChange("items[1]", "items[1]", ChangeKind.ADDED, null, null, null, "新商品"),
                new FieldChange("items[0]", "items[0]", ChangeKind.REMOVED, null, null, "旧商品", null)
        ));

        Result<String, CompareError> result = renderer.render(diff);

        assertThat(result.isOk()).isTrue();
        String text = result.get();
        assertThat(text).contains("订单金额：由「100」改为「120」");
        assertThat(text).contains("items[1]：新增「新商品」");
        assertThat(text).contains("items[0]：移除「旧商品」");
    }

    @Test
    void identicalResultRendersEmptyText() {
        PlainTextRenderer renderer = PlainTextRenderer.withDefaultTemplates();

        Result<String, CompareError> result = renderer.render(new DiffResult(List.of()));

        assertThat(result.isOk()).isTrue();
        assertThat(result.get()).isEmpty();
    }

    @Test
    void customTemplatesAreHonored() {
        PlainTextRenderer renderer = new PlainTextRenderer(
                "[MOD]{label}:{old}->{new}",
                "[ADD]{label}:{new}",
                "[DEL]{label}:{old}"
        );
        DiffResult diff = new DiffResult(List.of(
                new FieldChange("amount", "金额", ChangeKind.MODIFIED, null, null, "1", "2")
        ));

        String text = renderer.render(diff).get();

        assertThat(text).isEqualTo("[MOD]金额:1->2");
    }

    @Test
    void multipleChangesAreJoinedByNewline() {
        PlainTextRenderer renderer = PlainTextRenderer.withDefaultTemplates();
        DiffResult diff = new DiffResult(List.of(
                new FieldChange("a", "A", ChangeKind.MODIFIED, null, null, "1", "2"),
                new FieldChange("b", "B", ChangeKind.MODIFIED, null, null, "3", "4")
        ));

        String text = renderer.render(diff).get();

        assertThat(text.lines()).hasSize(2);
    }
}
