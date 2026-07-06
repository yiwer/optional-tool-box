package cn.code91.toolbox.storage.core;

/**
 * 入参/上传内容未通过校验（上传守卫拦截、local adapter 路径穿越 key 拒绝等）。
 *
 * @param message 说明具体被拦原因，供调用方直接展示/记录
 */
public record ValidationError(String message) implements StorageError {

    public static ValidationError of(String message) {
        return new ValidationError(message);
    }
}
