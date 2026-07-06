package cn.code91.toolbox.compare.engine;

import cn.code91.facility.locale.LocaleUtil;
import cn.code91.toolbox.compare.annotation.CompareLabel;

import java.lang.reflect.Field;

/**
 * {@code @CompareLabel} 标签解析：messageKey 经 facility {@code LocaleUtil} 解析，
 * 解析不到回退 {@code value}，{@code value} 为空回退字段名（裁定 A，两级回退）。
 */
final class LabelResolver {

    private LabelResolver() {
    }

    static String resolve(Field field) {
        String fieldName = field.getName();
        CompareLabel annotation = field.getAnnotation(CompareLabel.class);
        if (annotation == null) {
            return fieldName;
        }
        String fallbackPattern = annotation.value().isEmpty() ? fieldName : annotation.value();
        String messageKey = annotation.messageKey();
        if (messageKey.isEmpty()) {
            return fallbackPattern;
        }
        // fallbackPattern 走 MessageFormat 渲染；标签文本本身不含 {0} 占位符时原样返回。
        return LocaleUtil.translateMessageWithFallback(messageKey, null, fallbackPattern, LocaleUtil.getLocale());
    }
}
