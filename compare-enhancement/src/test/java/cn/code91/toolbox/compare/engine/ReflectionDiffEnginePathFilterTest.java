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
}
