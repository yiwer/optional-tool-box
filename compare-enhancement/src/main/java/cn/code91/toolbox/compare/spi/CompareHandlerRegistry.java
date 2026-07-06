package cn.code91.toolbox.compare.spi;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 类型级 {@link ValueComparator}/{@link ValueFormatter} 注册表。
 *
 * <p>装配顺序：内置 comparator/formatter 先注册 → 应用通过 {@code ObjectProvider} 收集的自定义
 * 实现覆盖同类型 → {@link CompareRegistryCustomizer} 最后定制 → {@link #freeze()} 冻结。
 * 冻结后为运行期不可变、线程安全的只读视图；冻结后调用 {@code register*} 抛 {@link IllegalStateException}。
 */
public final class CompareHandlerRegistry {

    private final Map<Class<?>, ValueComparator<?>> comparators = new ConcurrentHashMap<>();
    private final Map<Class<?>, ValueFormatter<?>> formatters = new ConcurrentHashMap<>();
    private volatile boolean frozen;

    /**
     * 创建仅含内置 comparator/formatter 的注册表（未冻结）。
     */
    public static CompareHandlerRegistry withBuiltins() {
        CompareHandlerRegistry registry = new CompareHandlerRegistry();
        registry.registerComparator(new BigDecimalComparator());
        return registry;
    }

    public <T> void registerComparator(ValueComparator<T> comparator) {
        Objects.requireNonNull(comparator, "comparator cannot be null");
        checkNotFrozen();
        comparators.put(comparator.type(), comparator);
    }

    public <T> void registerFormatter(ValueFormatter<T> formatter) {
        Objects.requireNonNull(formatter, "formatter cannot be null");
        checkNotFrozen();
        formatters.put(formatter.type(), formatter);
    }

    @SuppressWarnings("unchecked")
    public <T> ValueComparator<T> findComparator(Class<T> type) {
        return (ValueComparator<T>) comparators.get(type);
    }

    @SuppressWarnings("unchecked")
    public <T> ValueFormatter<T> findFormatter(Class<T> type) {
        return (ValueFormatter<T>) formatters.get(type);
    }

    /**
     * 冻结注册表，此后不可再注册。可重复调用（幂等）。
     */
    public void freeze() {
        frozen = true;
    }

    public boolean isFrozen() {
        return frozen;
    }

    private void checkNotFrozen() {
        if (frozen) {
            throw new IllegalStateException("CompareHandlerRegistry 已冻结，不可再注册");
        }
    }

    /**
     * 内置比较器：{@link BigDecimal} 用 {@code compareTo} 判等，规避 scale 陷阱（1.0 与 1.00 视为相等）。
     */
    private static final class BigDecimalComparator implements ValueComparator<BigDecimal> {
        @Override
        public Class<BigDecimal> type() {
            return BigDecimal.class;
        }

        @Override
        public boolean isEqual(BigDecimal a, BigDecimal b) {
            return a.compareTo(b) == 0;
        }
    }
}
