package cn.code91.toolbox.compare.core;

/**
 * 新旧两侧对象的运行时类型不一致，无法比较。
 */
public record TypeMismatch(String path, String message) implements CompareError {

    public static TypeMismatch of(String path, Class<?> oldType, Class<?> newType) {
        return new TypeMismatch(path, "类型不一致：oldType=" + oldType.getName() + ", newType=" + newType.getName());
    }
}
