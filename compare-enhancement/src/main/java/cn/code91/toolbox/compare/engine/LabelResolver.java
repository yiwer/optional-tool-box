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
        try {
            return LocaleUtil.translateMessageWithFallback(messageKey, null, fallbackPattern, LocaleUtil.getLocale());
        } catch (RuntimeException e) {
            // 停机竞态加固（M0/M1 终审 P2 候选）：diff() 唯一异常收口是 CompareAbortException，
            // 而 messageKey 解析经 facility SpringContextHolder 触达容器 MessageSource——应用
            // 停机（context close）窗口内并发 diff 可能抛 IllegalStateException 等运行时异常，
            // 任其穿透违反"对外 API 一律返回 Result"契约（00-overview §2 原则 3）。标签是
            // 展示性信息，解析失败退回注解回退文本即可，不值得让整个 diff 失败。
            return fallbackPattern;
        }
    }
}
