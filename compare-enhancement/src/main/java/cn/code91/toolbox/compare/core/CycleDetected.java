package cn.code91.toolbox.compare.core;

/**
 * 对象图递归栈中命中环（同一对象实例重复出现）。
 */
public record CycleDetected(String path, String message) implements CompareError {

    public static CycleDetected of(String path) {
        return new CycleDetected(path, "检测到对象引用环");
    }
}
