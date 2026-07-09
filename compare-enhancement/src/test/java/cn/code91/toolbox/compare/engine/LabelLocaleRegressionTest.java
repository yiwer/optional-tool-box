package cn.code91.toolbox.compare.engine;

import cn.code91.facility.context.SpringContextHolder;
import cn.code91.facility.result.Result;
import cn.code91.toolbox.compare.core.CompareError;
import cn.code91.toolbox.compare.core.DiffResult;
import cn.code91.toolbox.compare.spi.CompareHandlerRegistry;
import cn.code91.toolbox.compare.testfixtures.OrderBean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;

import java.math.BigDecimal;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归测试（I1）：{@code @CompareLabel(messageKey=...)} 的标签解析结果曾被
 * {@link ClassMetaCache} 以类维度永久缓存——首个触发线程的 locale 被后续所有请求继承，
 * 与调用方当时的 {@code LocaleContextHolder} locale 无关。
 *
 * <p>本类手动注入一个真实 {@link org.springframework.context.MessageSource}（经
 * {@link SpringContextHolder}，与生产 {@code toolboxCompareMessageSource} bean 同构的
 * {@code ResourceBundleMessageSource} 配置——{@code fallbackToSystemLocale=false} 等语义相同，
 * 仅 basename 指向 {@code src/test/resources} 下的测试专用 bundle：{@code order.amount} 示例键
 * 已随 I4 修复从生产 bundle 移出，此处复用同一测试 bundle 验证 messageKey 解析路径），
 * 复用<b>同一个</b> {@link ReflectionDiffEngine} 实例（从而复用同一个类级 {@link ClassMetaCache}）
 * 对同一个 bean 类型连续两次 diff，两次之间只切换 {@link LocaleContextHolder} 的 locale。
 *
 * <p><b>JVM 单例注意事项</b>：{@link SpringContextHolder} 是进程级单例，其
 * {@code clear()} 为包私有且仅通过 facility 测试专用桥暴露——该桥位于 facility
 * 的 {@code src/test}，未经 test-jar 发布，本模块无法引用，因此本类不 reset 它
 * （也没有其他 compare-enhancement 测试类触达 {@code SpringContextHolder}，
 * 先到先得的单次注入是安全的）。{@link LocaleContextHolder} 是 ThreadLocal，
 * 用 {@code @AfterEach} 复位，避免影响同一线程后续用例。
 *
 * <p>另含停机竞态加固回归（@Order 末位）：关闭本类注入的上下文后验证标签解析回退——
 * close 后其余 compare 测试不受影响（仅本类断言标签值，其余测试只断言 path/text）。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LabelLocaleRegressionTest {

    private static final ReflectionDiffEngine ENGINE = new ReflectionDiffEngine(CompareHandlerRegistry.withBuiltins());

    /** 静态注入的真实上下文；@Order 末位用例会将其 close 以复现停机竞态（见类 Javadoc 追注）。 */
    private static final AnnotationConfigApplicationContext CONTEXT;

    static {
        // 与生产 ToolboxCompareAutoConfiguration#toolboxCompareMessageSource 同构（除 basename
        // 指向测试专用 bundle 外）的真实 MessageSource，经由 SpringContextHolder 暴露给
        // LocaleUtil（其内部即经此单例查找）。
        CONTEXT = new AnnotationConfigApplicationContext(MessageSourceConfig.class);
        SpringContextHolder.setApplicationContextManually(CONTEXT);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @Order(1)
    void sameFieldLabelReflectsCurrentLocalePerDiffCallNotFirstCallerLocale() {
        OrderBean beforeZh = new OrderBean();
        beforeZh.setAmount(new BigDecimal("100.00"));
        OrderBean afterZh = new OrderBean();
        afterZh.setAmount(new BigDecimal("200.00"));

        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        DiffResult zhResult = ENGINE.diff(beforeZh, afterZh).get();
        String zhLabel = labelOf(zhResult, "amount");

        OrderBean beforeEn = new OrderBean();
        beforeEn.setAmount(new BigDecimal("100.00"));
        OrderBean afterEn = new OrderBean();
        afterEn.setAmount(new BigDecimal("200.00"));

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        DiffResult enResult = ENGINE.diff(beforeEn, afterEn).get();
        String enLabel = labelOf(enResult, "amount");

        assertThat(zhLabel)
                .as("首次以中文 locale 触发 diff，amount 字段标签应解析为中文")
                .isEqualTo("订单金额");
        assertThat(enLabel)
                .as("同一 JVM、同一引擎实例，切换到英文 locale 后再次 diff，"
                        + "amount 字段标签应解析为英文——而非被类级缓存冻结为第一次的中文")
                .isEqualTo("Order Amount");
    }

    /**
     * 停机竞态加固（M0/M1 终审 P2 候选）：SpringContextHolder 持有的上下文关闭后，
     * messageKey 解析不得让运行时异常穿透 diff()——应退回 @CompareLabel 注解回退文本。
     * 用英文 locale 断言：正常路径解析出 "Order Amount"，回退路径是注解 value "订单金额"，
     * 两者可区分（中文 locale 下解析结果与回退文本同为"订单金额"，无鉴别力）。
     * 本用例必须最后执行（@Order(2)）——close 不可逆，SpringContextHolder 无法 reset。
     */
    @Test
    @Order(2)
    void labelResolutionFallsBackInsteadOfThrowingWhenContextClosed() {
        CONTEXT.close();

        OrderBean before = new OrderBean();
        before.setAmount(new BigDecimal("100.00"));
        OrderBean after = new OrderBean();
        after.setAmount(new BigDecimal("200.00"));

        LocaleContextHolder.setLocale(Locale.ENGLISH);
        Result<DiffResult, CompareError> result = ENGINE.diff(before, after);

        assertThat(result.isOk())
                .as("MessageSource 上下文已关闭时 diff 不得异常穿透，应正常返回 Ok").isTrue();
        assertThat(labelOf(result.get(), "amount"))
                .as("messageKey 解析失败应退回 @CompareLabel 注解回退文本（而非英文解析结果）")
                .isEqualTo("订单金额");
    }

    private static String labelOf(DiffResult result, String fieldName) {
        return result.changes().stream()
                .filter(change -> change.path().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到字段 " + fieldName + " 的变更记录：" + result.changes()))
                .label();
    }

    @Configuration
    static class MessageSourceConfig {
        @Bean
        ResourceBundleMessageSource messageSource() {
            ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
            // I4 修复后 order.amount 已从生产 bundle 移出，测试改用
            // src/test/resources/i18n/test-compare-messages*.properties。
            messageSource.setBasename("i18n/test-compare-messages");
            messageSource.setDefaultEncoding("UTF-8");
            messageSource.setUseCodeAsDefaultMessage(false);
            messageSource.setFallbackToSystemLocale(false);
            return messageSource;
        }
    }
}
