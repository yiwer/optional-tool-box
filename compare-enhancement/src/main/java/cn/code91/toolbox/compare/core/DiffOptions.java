package cn.code91.toolbox.compare.core;

import java.util.Objects;
import java.util.Set;

/**
 * 差异对比选项。
 *
 * @param maxDepth     对象图递归深度上限（默认 8）
 * @param nullAsEmpty  null 与空串是否视为相等（默认 false）
 * @param includePaths 仅比较这些路径（前缀匹配，深路径的祖先自动放行以便下钻）；为空表示不限制
 * @param excludePaths 排除这些路径（前缀匹配）；为空表示不排除
 */
public record DiffOptions(int maxDepth, boolean nullAsEmpty, Set<String> includePaths, Set<String> excludePaths) {

    private static final int DEFAULT_MAX_DEPTH = 8;

    public DiffOptions {
        Objects.requireNonNull(includePaths, "includePaths cannot be null");
        Objects.requireNonNull(excludePaths, "excludePaths cannot be null");
        if (maxDepth <= 0) {
            throw new IllegalArgumentException("maxDepth must be positive, got " + maxDepth);
        }
    }

    /**
     * 默认选项：maxDepth=8、nullAsEmpty=false、不限制路径。
     */
    public static DiffOptions defaults() {
        return new DiffOptions(DEFAULT_MAX_DEPTH, false, Set.of(), Set.of());
    }

    /**
     * 是否应比较给定路径（同时满足 include 与 exclude 约束）。
     *
     * <p>include 语义（P2 深路径修复）：path 命中某 include 前缀自身/其后代，<b>或是某个
     * include 前缀的祖先</b>时放行——祖先放行仅为允许引擎逐层下钻到目标深路径（include
     * {@code "a.b"} 时顶层字段 {@code a} 必须先放行才可能到达 {@code a.b}）。已知语义边界：
     * 若祖先字段本身是叶子值（或配有 {@code @CompareWith}/类型级比较器），它会被整体比较并
     * 可能产出自身的变更记录——include 深路径的本意是对象图下钻，对叶子祖先不适用。</p>
     *
     * <p>另一已知边界：include/exclude 过滤在引擎的字段边界（compareField）逐字段咨询本方法——
     * 结构体元素的内部字段带完整路径重新经过该门（include {@code "items[0]"} 时
     * {@code items[1].price} 的变更会被正确过滤），但两类元素级变更记录不经过滤：
     * <b>叶子类型的集合/Map 元素</b>（元素直接比较产出，不经字段门），以及<b>长度/键集
     * 不匹配产生的 ADDED/REMOVED 元素</b>。include 深路径穿越集合时，这两类兄弟元素的
     * 变更仍会产出；元素级过滤留待后续独立裁定。</p>
     *
     * <p>exclude 无祖先语义：exclude {@code "a.b"} 只排除 {@code a.b} 及其后代，不影响
     * {@code a} 与 {@code a.c}。</p>
     */
    public boolean isPathIncluded(String path) {
        boolean includeOk = includePaths.isEmpty() || matchesAnyIncludePrefix(path);
        boolean excludeOk = excludePaths.isEmpty() || !matchesAny(path, excludePaths);
        return includeOk && excludeOk;
    }

    /** include 方向：自身/后代（同 exclude 的前缀语义），另放行 include 前缀的祖先。 */
    private boolean matchesAnyIncludePrefix(String path) {
        for (String prefix : includePaths) {
            if (matchesPrefix(path, prefix) || isAncestorOf(path, prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(String path, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (matchesPrefix(path, prefix)) {
                return true;
            }
        }
        return false;
    }

    /** path 即 prefix 本身或其后代（{@code prefix.}/{@code prefix[} 分隔，避免 address/addressBook 误判）。 */
    private static boolean matchesPrefix(String path, String prefix) {
        return path.equals(prefix) || path.startsWith(prefix + ".") || path.startsWith(prefix + "[");
    }

    /** path 是 prefix 的祖先（prefix 位于 path 之下，如 path={@code a}、prefix={@code a.b} 或 {@code a[0]}）。 */
    private static boolean isAncestorOf(String path, String prefix) {
        return prefix.startsWith(path + ".") || prefix.startsWith(path + "[");
    }
}
