package cn.code91.toolbox.compare.spi;

/**
 * 值格式化扩展点：将值渲染为展示文本。
 *
 * @param <T> 格式化的值类型
 */
public interface ValueFormatter<T> {

    /**
     * 声明本格式化器适用的类型，供类型级注册使用。
     */
    Class<T> type();

    /**
     * 将值格式化为展示文本。
     */
    String format(T value);
}
