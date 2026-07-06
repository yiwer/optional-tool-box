package cn.code91.toolbox.compare.core;

/**
 * 字段读取失败（反射异常）。
 */
public record FieldAccessError(String path, String message) implements CompareError {

    public static FieldAccessError of(String path, Throwable cause) {
        return new FieldAccessError(path, "字段读取失败：" + cause.getMessage());
    }
}
