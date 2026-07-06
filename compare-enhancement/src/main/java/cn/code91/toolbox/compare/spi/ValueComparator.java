package cn.code91.toolbox.compare.spi;

/**
 * 值比较器扩展点：判定两个同类型值是否相等。
 *
 * @param <T> 比较的值类型
 */
public interface ValueComparator<T> {

    /**
     * 声明本比较器适用的类型，供类型级注册使用。
     */
    Class<T> type();

    /**
     * 判定两个值是否相等。
     */
    boolean isEqual(T a, T b);
}
