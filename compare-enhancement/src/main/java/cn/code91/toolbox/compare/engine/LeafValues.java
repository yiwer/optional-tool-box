package cn.code91.toolbox.compare.engine;

import cn.code91.facility.date.DateUtil;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAccessor;

/**
 * 叶子类型判定与展示文本渲染。叶子类型直接比较，不再递归展开字段。
 */
final class LeafValues {

    private LeafValues() {
    }

    /**
     * 是否为叶子类型：基本类型/包装、String、枚举、日期时间、BigDecimal。
     * 数值类型统一视为叶子（不含 BigDecimal 以外的 Number 特殊判等逻辑，走 equals）。
     */
    static boolean isLeaf(Class<?> type) {
        return type.isPrimitive()
                || type.isEnum()
                || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type)
                || Boolean.class.isAssignableFrom(type)
                || Character.class.isAssignableFrom(type)
                || TemporalAccessor.class.isAssignableFrom(type);
    }

    /**
     * 渲染叶子值的展示文本。日期时间经 facility {@code DateUtil} 按 {@code datePattern} 渲染；
     * 其余类型用 {@code toString()}（枚举即 {@code name()}）。
     */
    static String toText(Object value, String datePattern) {
        if (value == null) {
            return null;
        }
        if (value instanceof TemporalAccessor temporal) {
            return DateUtil.format(temporal, datePattern).orElse(value.toString());
        }
        return value.toString();
    }

    /**
     * 判定两个叶子值是否相等：{@link BigDecimal} 用 {@code compareTo} 规避 scale 陷阱（1.0 与 1.00 相等）；
     * 其余类型用 {@code equals}。
     */
    static boolean equalsLeaf(Object a, Object b) {
        if (a instanceof BigDecimal bd1 && b instanceof BigDecimal bd2) {
            return bd1.compareTo(bd2) == 0;
        }
        return java.util.Objects.equals(a, b);
    }

    /**
     * 校验类型是否受支持的日期时间叶子类型（供文档/测试自检使用）。
     */
    static boolean isSupportedTemporal(Class<?> type) {
        return LocalDate.class.isAssignableFrom(type)
                || LocalDateTime.class.isAssignableFrom(type)
                || OffsetDateTime.class.isAssignableFrom(type);
    }
}
