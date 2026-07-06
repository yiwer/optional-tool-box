package cn.code91.toolbox.compare.engine;

import cn.code91.toolbox.compare.core.ChangeKind;
import cn.code91.toolbox.compare.core.DiffResult;
import cn.code91.toolbox.compare.spi.CompareHandlerRegistry;
import cn.code91.toolbox.compare.testfixtures.AddressBean;
import cn.code91.toolbox.compare.testfixtures.ItemBean;
import cn.code91.toolbox.compare.testfixtures.OrderBean;
import cn.code91.toolbox.compare.testfixtures.TagsBean;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 嵌套对象递归展开 + BY_INDEX 集合（List/数组）+ Map 按 key 配对（裁定 B 收窄范围）。
 */
class ReflectionDiffEngineStructureTest {

    private final ReflectionDiffEngine engine = new ReflectionDiffEngine(CompareHandlerRegistry.withBuiltins());

    @Test
    void nestedObjectChangeProducesDottedPath() {
        OrderBean before = new OrderBean();
        before.setAddress(new AddressBean("北京", "朝阳区"));
        OrderBean after = new OrderBean();
        after.setAddress(new AddressBean("上海", "朝阳区"));

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.changes()).hasSize(1);
        var change = result.changes().get(0);
        assertThat(change.path()).isEqualTo("address.city");
        assertThat(change.label()).isEqualTo("城市");
        assertThat(change.oldText()).isEqualTo("北京");
        assertThat(change.newText()).isEqualTo("上海");
    }

    @Test
    void nestedObjectAddedWhenOldIsNull() {
        OrderBean before = new OrderBean();
        before.setAddress(null);
        OrderBean after = new OrderBean();
        after.setAddress(new AddressBean("北京", "朝阳区"));

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).path()).isEqualTo("address");
        assertThat(result.changes().get(0).kind()).isEqualTo(ChangeKind.ADDED);
    }

    @Test
    void byIndexCollectionEqualLengthModification() {
        OrderBean before = new OrderBean();
        before.setItems(List.of(new ItemBean("A", new BigDecimal("10.00")), new ItemBean("B", new BigDecimal("20.00"))));
        OrderBean after = new OrderBean();
        after.setItems(List.of(new ItemBean("A", new BigDecimal("15.00")), new ItemBean("B", new BigDecimal("20.00"))));

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).path()).isEqualTo("items[0].price");
        assertThat(result.changes().get(0).kind()).isEqualTo(ChangeKind.MODIFIED);
    }

    @Test
    void byIndexCollectionExtraElementsOnNewSideAreAdded() {
        OrderBean before = new OrderBean();
        before.setItems(List.of(new ItemBean("A", new BigDecimal("10.00"))));
        OrderBean after = new OrderBean();
        after.setItems(List.of(new ItemBean("A", new BigDecimal("10.00")), new ItemBean("B", new BigDecimal("20.00"))));

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).path()).isEqualTo("items[1]");
        assertThat(result.changes().get(0).kind()).isEqualTo(ChangeKind.ADDED);
    }

    @Test
    void byIndexCollectionExtraElementsOnOldSideAreRemoved() {
        OrderBean before = new OrderBean();
        before.setItems(List.of(new ItemBean("A", new BigDecimal("10.00")), new ItemBean("B", new BigDecimal("20.00"))));
        OrderBean after = new OrderBean();
        after.setItems(List.of(new ItemBean("A", new BigDecimal("10.00"))));

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).path()).isEqualTo("items[1]");
        assertThat(result.changes().get(0).kind()).isEqualTo(ChangeKind.REMOVED);
    }

    @Test
    void arrayFieldSupportsByIndexComparison() {
        IntArrayHolder before = new IntArrayHolder(new int[]{1, 2, 3});
        IntArrayHolder after = new IntArrayHolder(new int[]{1, 9, 3});

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).path()).isEqualTo("values[1]");
    }

    @Test
    void mapFieldPairsByKeyAndReportsAddedRemovedModified() {
        TagsBean before = new TagsBean();
        Map<String, String> beforeTags = new LinkedHashMap<>();
        beforeTags.put("color", "red");
        beforeTags.put("removedKey", "gone");
        before.setTags(beforeTags);

        TagsBean after = new TagsBean();
        Map<String, String> afterTags = new LinkedHashMap<>();
        afterTags.put("color", "blue");
        afterTags.put("addedKey", "new");
        after.setTags(afterTags);

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.changes())
                .extracting("path", "kind")
                .containsExactlyInAnyOrder(
                        tuple("tags[color]", ChangeKind.MODIFIED),
                        tuple("tags[removedKey]", ChangeKind.REMOVED),
                        tuple("tags[addedKey]", ChangeKind.ADDED)
                );
    }

    @Test
    void mapFieldIdenticalContentProducesNoChange() {
        TagsBean before = new TagsBean();
        before.setTags(Map.of("k", "v"));
        TagsBean after = new TagsBean();
        after.setTags(Map.of("k", "v"));

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.identical()).isTrue();
    }

    /**
     * 数组字段测试夹具，内联定义以避免额外文件（仅本用例使用）。
     */
    public static final class IntArrayHolder {
        private final int[] values;

        public IntArrayHolder(int[] values) {
            this.values = values;
        }

        public int[] getValues() {
            return values;
        }
    }
}
