package cn.code91.toolbox.compare.core;

/**
 * 对象图递归深度超过 {@link DiffOptions#maxDepth()}。
 */
public record DepthExceeded(String path, String message) implements CompareError {

    public static DepthExceeded of(String path, int maxDepth) {
        return new DepthExceeded(path, "递归深度超过上限：maxDepth=" + maxDepth);
    }
}
