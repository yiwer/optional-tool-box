package cn.code91.toolbox.compare.core;

import java.util.Objects;
import java.util.Set;

/**
 * 差异对比选项。
 *
 * @param maxDepth     对象图递归深度上限（默认 8）
 * @param nullAsEmpty  null 与空串是否视为相等（默认 false）
 * @param includePaths 仅比较这些路径（前缀匹配）；为空表示不限制
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
     */
    public boolean isPathIncluded(String path) {
        boolean includeOk = includePaths.isEmpty() || matchesAny(path, includePaths);
        boolean excludeOk = excludePaths.isEmpty() || !matchesAny(path, excludePaths);
        return includeOk && excludeOk;
    }

    private static boolean matchesAny(String path, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (path.equals(prefix) || path.startsWith(prefix + ".") || path.startsWith(prefix + "[")) {
                return true;
            }
        }
        return false;
    }
}
