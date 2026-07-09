package cn.code91.toolbox.compare.core;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link DiffOptions} 校验与路径过滤（includePaths/excludePaths）行为。
 */
class DiffOptionsTest {

    @Test
    void defaultsHaveExpectedValues() {
        DiffOptions options = DiffOptions.defaults();

        assertThat(options.maxDepth()).isEqualTo(8);
        assertThat(options.nullAsEmpty()).isFalse();
        assertThat(options.includePaths()).isEmpty();
        assertThat(options.excludePaths()).isEmpty();
    }

    @Test
    void nonPositiveMaxDepthIsRejected() {
        assertThatThrownBy(() -> new DiffOptions(0, false, Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DiffOptions(-1, false, Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullIncludeOrExcludePathsAreRejected() {
        assertThatThrownBy(() -> new DiffOptions(8, false, null, Set.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new DiffOptions(8, false, Set.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void emptyIncludeAndExcludeAllowsAnyPath() {
        DiffOptions options = new DiffOptions(8, false, Set.of(), Set.of());

        assertThat(options.isPathIncluded("amount")).isTrue();
        assertThat(options.isPathIncluded("address.city")).isTrue();
    }

    @Test
    void includePathsRestrictsToMatchingPrefixesOnly() {
        DiffOptions options = new DiffOptions(8, false, Set.of("address"), Set.of());

        assertThat(options.isPathIncluded("address")).as("精确匹配").isTrue();
        assertThat(options.isPathIncluded("address.city")).as("点号子路径").isTrue();
        assertThat(options.isPathIncluded("items[0]")).as("未在 include 列表中").isFalse();
    }

    @Test
    void includePathsMatchesIndexedSubPath() {
        DiffOptions options = new DiffOptions(8, false, Set.of("items"), Set.of());

        assertThat(options.isPathIncluded("items[0]")).as("下标子路径").isTrue();
        assertThat(options.isPathIncluded("amount")).isFalse();
    }

    @Test
    void excludePathsRemovesMatchingPrefixes() {
        DiffOptions options = new DiffOptions(8, false, Set.of(), Set.of("address"));

        assertThat(options.isPathIncluded("address.city")).isFalse();
        assertThat(options.isPathIncluded("amount")).isTrue();
    }

    @Test
    void excludeTakesPrecedenceOverInclude() {
        DiffOptions options = new DiffOptions(8, false, Set.of("address"), Set.of("address.secret"));

        assertThat(options.isPathIncluded("address.city")).isTrue();
        assertThat(options.isPathIncluded("address.secret")).as("同时命中 include 与 exclude 时以 exclude 为准").isFalse();
    }

    @Test
    void unrelatedPrefixDoesNotFalsePositiveMatch() {
        DiffOptions options = new DiffOptions(8, false, Set.of("address"), Set.of());

        assertThat(options.isPathIncluded("addressBook")).as("字符串前缀相同但非路径分隔不应误判为子路径").isFalse();
    }

    @Test
    void deepIncludePathAllowsDescendingThroughAncestors() {
        DiffOptions options = new DiffOptions(8, false, Set.of("address.city"), Set.of());

        assertThat(options.isPathIncluded("address"))
                .as("祖先必须放行，否则引擎在顶层就跳过 address，永远下钻不到 address.city").isTrue();
        assertThat(options.isPathIncluded("address.city")).as("目标深路径本身").isTrue();
        assertThat(options.isPathIncluded("address.city.code")).as("目标之下的后代").isTrue();
        assertThat(options.isPathIncluded("address.street")).as("目标的兄弟不放行").isFalse();
        assertThat(options.isPathIncluded("amount")).as("无关顶层字段不放行").isFalse();
        assertThat(options.isPathIncluded("addressBook"))
                .as("字符串前缀相同但非路径分隔不得误判为祖先").isFalse();
    }

    @Test
    void deepIncludeWithIndexedPathAllowsAncestors() {
        DiffOptions options = new DiffOptions(8, false, Set.of("items[0].price"), Set.of());

        assertThat(options.isPathIncluded("items")).as("下标路径的顶层祖先").isTrue();
        assertThat(options.isPathIncluded("items[0]")).isTrue();
        assertThat(options.isPathIncluded("items[0].price")).isTrue();
        assertThat(options.isPathIncluded("items[1]")).as("其它下标不是 items[0].price 的祖先").isFalse();
    }

    @Test
    void excludeDoesNotGainAncestorSemantics() {
        DiffOptions options = new DiffOptions(8, false, Set.of(), Set.of("address.city"));

        assertThat(options.isPathIncluded("address")).as("exclude 深路径不得连带排除祖先").isTrue();
        assertThat(options.isPathIncluded("address.city")).isFalse();
        assertThat(options.isPathIncluded("address.street")).isTrue();
    }
}
