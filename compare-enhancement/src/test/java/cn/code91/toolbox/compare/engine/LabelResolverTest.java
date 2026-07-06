package cn.code91.toolbox.compare.engine;

import cn.code91.toolbox.compare.annotation.CompareLabel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @CompareLabel} 标签解析的两级回退（裁定 A）：
 * messageKey 解析不到 → 回退 value；value 为空 → 回退字段名。
 * 本类不启动 Spring 上下文，因此 messageKey 恒解析不到，专注验证回退链路；
 * messageKey 成功解析的分支见装配层测试（ToolboxCompareAutoConfigurationTest）。
 */
class LabelResolverTest {

    @Test
    void noAnnotationFallsBackToFieldName() throws NoSuchFieldException {
        Field field = Plain.class.getDeclaredField("plainField");

        assertThat(LabelResolver.resolve(field)).isEqualTo("plainField");
    }

    @Test
    void blankValueAndNoMessageKeyFallsBackToFieldName() throws NoSuchFieldException {
        Field field = Plain.class.getDeclaredField("blankLabelField");

        assertThat(LabelResolver.resolve(field)).isEqualTo("blankLabelField");
    }

    @Test
    void valueIsUsedWhenNoMessageKeyProvided() throws NoSuchFieldException {
        Field field = Plain.class.getDeclaredField("valueOnlyField");

        assertThat(LabelResolver.resolve(field)).isEqualTo("展示值");
    }

    @Test
    void unresolvedMessageKeyFallsBackToValue() throws NoSuchFieldException {
        Field field = Plain.class.getDeclaredField("messageKeyField");

        assertThat(LabelResolver.resolve(field))
                .as("无 Spring MessageSource 时 messageKey 解析不到，应回退 value")
                .isEqualTo("兜底值");
    }

    @Test
    void unresolvedMessageKeyWithBlankValueFallsBackToFieldName() throws NoSuchFieldException {
        Field field = Plain.class.getDeclaredField("messageKeyOnlyField");

        assertThat(LabelResolver.resolve(field)).isEqualTo("messageKeyOnlyField");
    }

    @SuppressWarnings("unused")
    private static final class Plain {
        String plainField;

        @CompareLabel("")
        String blankLabelField;

        @CompareLabel("展示值")
        String valueOnlyField;

        @CompareLabel(value = "兜底值", messageKey = "not.registered.key")
        String messageKeyField;

        @CompareLabel(messageKey = "not.registered.key")
        String messageKeyOnlyField;
    }
}
