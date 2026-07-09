package cn.code91.toolbox.compare.engine;

import cn.code91.toolbox.compare.core.ChangeKind;
import cn.code91.toolbox.compare.core.DiffOptions;
import cn.code91.toolbox.compare.core.DiffResult;
import cn.code91.toolbox.compare.spi.CompareHandlerRegistry;
import cn.code91.toolbox.compare.testfixtures.AddressBean;
import cn.code91.toolbox.compare.testfixtures.ItemBean;
import cn.code91.toolbox.compare.testfixtures.OrderBean;
import cn.code91.toolbox.compare.testfixtures.TagsBean;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@link DiffOptions#includePaths()}/{@link DiffOptions#excludePaths()} 经引擎实际生效验证。
 */
class ReflectionDiffEnginePathFilterTest {

    private final ReflectionDiffEngine engine = new ReflectionDiffEngine(CompareHandlerRegistry.withBuiltins());

    @Test
    void excludePathsSkipsMatchingFieldEvenWhenChanged() {
        OrderBean before = new OrderBean();
        before.setAmount(new BigDecimal("100.00"));
        before.setRemark("旧");
        OrderBean after = new OrderBean();
        after.setAmount(new BigDecimal("120.00"));
        after.setRemark("新");

        DiffOptions options = new DiffOptions(8, false, Set.of(), Set.of("amount"));
        DiffResult result = engine.diff(before, after, options).get();

        assertThat(result.changes()).extracting("path").containsExactly("remark");
    }

    @Test
    void includePathsOnlyComparesMatchingFieldAndItsSubPaths() {
        OrderBean before = new OrderBean();
        before.setAmount(new BigDecimal("100.00"));
        before.setAddress(new AddressBean("北京", "朝阳区"));
        OrderBean after = new OrderBean();
        after.setAmount(new BigDecimal("120.00"));
        after.setAddress(new AddressBean("上海", "朝阳区"));

        DiffOptions options = new DiffOptions(8, false, Set.of("address"), Set.of());
        DiffResult result = engine.diff(before, after, options).get();

        assertThat(result.changes()).extracting("path").containsExactly("address.city");
    }

    @Test
    void deepIncludePathReachesNestedFieldThroughAncestors() {
        OrderBean before = new OrderBean();
        before.setAmount(new BigDecimal("100.00"));
        before.setAddress(new AddressBean("北京", "朝阳区"));
        OrderBean after = new OrderBean();
        after.setAmount(new BigDecimal("120.00"));
        after.setAddress(new AddressBean("上海", "海淀区"));

        DiffOptions options = new DiffOptions(8, false, Set.of("address.city"), Set.of());
        DiffResult result = engine.diff(before, after, options).get();

        assertThat(result.changes()).extracting("path")
                .as("include 深路径应下钻命中 address.city；amount 与 address 下的兄弟字段均不产出")
                .containsExactly("address.city");
    }

    @Test
    void leafAncestorOfDeepIncludeIsComparedWholesale() {
        // 钉住 DiffOptions#isPathIncluded Javadoc 披露的语义边界:include 深路径的祖先若本身
        // 是叶子字段,会被整体比较并产出自身的变更记录(include 深路径的本意是对象图下钻,
        // 对叶子祖先不适用)。若未来重构改变此行为,本测试将其暴露为显式决策而非静默漂移。
        OrderBean before = new OrderBean();
        before.setRemark("旧备注");
        OrderBean after = new OrderBean();
        after.setRemark("新备注");

        DiffOptions options = new DiffOptions(8, false, Set.of("remark.x"), Set.of());
        DiffResult result = engine.diff(before, after, options).get();

        assertThat(result.changes()).extracting("path")
                .as("叶子祖先 remark 被整体比较——文档化边界的回归钉")
                .containsExactly("remark");
    }

    @Test
    void includeDoesNotFilterSizeMismatchAddedElements() {
        // 回归钉(整分支终审 Important):include/exclude 过滤仅在字段边界(compareField)逐字段
        // 咨询 isPathIncluded,长度不匹配产生的 ADDED/REMOVED 元素记录由集合遍历直接产出、
        // 不经过滤门——include 穿越集合时兄弟元素(items[1])的新增仍会产出。这是已披露的语义
        // 边界(见 DiffOptions#isPathIncluded Javadoc);对照:同尺寸结构体元素的内部字段带完整
        // 路径重新过字段门,不泄漏。元素级过滤的正解留待后续独立裁定;若未来实现元素级过滤,
        // 本测试应随该决策显式翻转(预期从"泄漏"变为"被滤掉")。
        OrderBean before = new OrderBean();
        before.setItems(List.of(new ItemBean("A", new BigDecimal("1.00"))));
        OrderBean after = new OrderBean();
        after.setItems(List.of(
                new ItemBean("A", new BigDecimal("1.00")),
                new ItemBean("B", new BigDecimal("2.00"))));

        DiffOptions options = new DiffOptions(8, false, Set.of("items[0]"), Set.of());
        DiffResult result = engine.diff(before, after, options).get();

        assertThat(result.changes())
                .as("长度不匹配的 ADDED 元素不经 include 过滤——兄弟元素 items[1] 的新增记录仍产出")
                .extracting("path", "kind")
                .containsExactly(tuple("items[1]", ChangeKind.ADDED));
    }

    @Test
    void includeDoesNotFilterLeafTypedSiblingElements() {
        // 回归钉(整分支终审 Important):叶子类型的集合/Map 元素在 compare 中直接比较产出
        // 变更记录,不经字段边界的过滤门(isPathIncluded 仅在 compareField 被咨询)——include
        // 穿越 Map 时兄弟键(tags[b])的值变更仍会产出。这是已披露的语义边界(见
        // DiffOptions#isPathIncluded Javadoc),元素级过滤的正解留待后续独立裁定;若未来实现
        // 元素级过滤,本测试应随该决策显式翻转(预期从"泄漏"变为"被滤掉")。
        TagsBean before = new TagsBean();
        before.setTags(Map.of("a", "1", "b", "2"));
        TagsBean after = new TagsBean();
        after.setTags(Map.of("a", "1", "b", "3"));

        DiffOptions options = new DiffOptions(8, false, Set.of("tags[a]"), Set.of());
        DiffResult result = engine.diff(before, after, options).get();

        assertThat(result.changes())
                .as("叶子类型 Map 元素不经 include 过滤——兄弟键 tags[b] 的变更仍产出")
                .extracting("path", "kind")
                .containsExactly(tuple("tags[b]", ChangeKind.MODIFIED));
    }
}
