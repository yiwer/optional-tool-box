package cn.code91.toolbox.compare.annotation;

import cn.code91.toolbox.compare.spi.ValueComparator;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段级自定义比较器，查找优先级最高（高于应用注册的类型级比较器与内置比较器）。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CompareWith {

    /**
     * 比较器实现类，需提供无参构造函数。
     */
    Class<? extends ValueComparator<?>> value();
}
