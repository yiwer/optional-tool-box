package cn.code91.toolbox.compare.core;

/**
 * 渲染过程失败（如 JSON 序列化异常）。渲染错误定位不到具体字段，{@link #path()} 恒为空串。
 */
public record RenderError(String message) implements CompareError {

    @Override
    public String path() {
        return "";
    }

    public static RenderError of(Throwable cause) {
        return new RenderError("渲染失败：" + cause.getMessage());
    }
}
