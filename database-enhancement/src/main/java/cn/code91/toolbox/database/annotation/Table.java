package cn.code91.toolbox.database.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <b>显式声明实体对应的物理表名</b>
 * <p>P1 仅含 {@code value}（无 schema/catalog 等）——接口面最小，附加属性按需后补。</p>
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface Table {

    /** 物理表名（非空）。 */
    String value();
}
