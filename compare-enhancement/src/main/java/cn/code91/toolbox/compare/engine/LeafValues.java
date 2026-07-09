package cn.code91.toolbox.compare.engine;

import cn.code91.facility.date.DateUtil;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.UUID;

/**
 * 叶子类型判定与展示文本渲染。叶子类型直接比较，不再递归展开字段。
 */
final class LeafValues {

    private LeafValues() {
    }

    /**
     * 是否为叶子类型：基本类型/包装、String、枚举（含带方法体常量的匿名子类——其
     * {@code Class.isEnum()} 为 false，故用 {@code Enum} 可赋值性判定）、日期时间
     * （{@code TemporalAccessor} 与 {@code java.util.Date} 含其 {@code java.sql} 子类，P2 扩表）、
     * {@code UUID}（P2 扩表）、BigDecimal。数值类型统一视为叶子（不含 BigDecimal 以外的 Number
     * 特殊判等逻辑，走 equals）。
     */
    static boolean isLeaf(Class<?> type) {
        return type.isPrimitive()
                || Enum.class.isAssignableFrom(type)
                || CharSequence.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type)
                || Boolean.class.isAssignableFrom(type)
                || Character.class.isAssignableFrom(type)
                || TemporalAccessor.class.isAssignableFrom(type)
                || Date.class.isAssignableFrom(type)
                || UUID.class == type;
    }

    /**
     * 渲染叶子值的展示文本。日期时间经 facility {@code DateUtil} 按 {@code datePattern} 渲染
     * （{@code Date} 经 epoch 毫秒转 {@code LocalDateTime}——不用 {@code Date#toInstant()}，
     * {@code java.sql.Date}/{@code java.sql.Time} 对其抛 {@code UnsupportedOperationException}）；
     * 其余类型用 {@code toString()}（枚举即 {@code name()}）。
     */
    static String toText(Object value, String datePattern) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum<?> enumValue) {
            // 带方法体常量可能覆写 toString；展示文本固定取 name()，保证稳定可读。
            return enumValue.name();
        }
        if (value instanceof TemporalAccessor temporal) {
            return DateUtil.format(temporal, datePattern).orElse(value.toString());
        }
        if (value instanceof Date date) {
            return DateUtil.format(
                            Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDateTime(),
                            datePattern)
                    .orElse(value.toString());
        }
        return value.toString();
    }

    /**
     * 判定两个叶子值是否相等：{@link BigDecimal} 用 {@code compareTo} 规避 scale 陷阱（1.0 与 1.00 相等）；
     * 其余类型用 {@code equals}（{@code Date}/{@code UUID} 的 equals 均为值语义；{@code Timestamp}
     * 对 {@code Date} 的非对称 equals 不可达——类型对检查要求两侧运行时类相同，枚举除外
     * （见 {@code ReflectionDiffEngine#isComparableTypePair} 的 Enum 分支；但
     * {@code Timestamp}/{@code Date} 均非枚举，结论在此语境仍成立）。
     */
    static boolean equalsLeaf(Object a, Object b) {
        if (a instanceof BigDecimal bd1 && b instanceof BigDecimal bd2) {
            return bd1.compareTo(bd2) == 0;
        }
        return java.util.Objects.equals(a, b);
    }
}
