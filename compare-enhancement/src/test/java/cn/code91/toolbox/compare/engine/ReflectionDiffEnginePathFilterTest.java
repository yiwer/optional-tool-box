package cn.code91.toolbox.compare.engine;

import cn.code91.toolbox.compare.core.DiffOptions;
import cn.code91.toolbox.compare.core.DiffResult;
import cn.code91.toolbox.compare.spi.CompareHandlerRegistry;
import cn.code91.toolbox.compare.testfixtures.AddressBean;
import cn.code91.toolbox.compare.testfixtures.OrderBean;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
}
