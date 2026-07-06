package cn.code91.toolbox.compare.engine;

import cn.code91.toolbox.compare.core.DiffResult;
import cn.code91.toolbox.compare.spi.CompareHandlerRegistry;
import cn.code91.toolbox.compare.spi.ValueFormatter;
import cn.code91.toolbox.compare.testfixtures.OrderBean;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用注册的类型级 {@link ValueFormatter} 应被引擎用于渲染展示文本（oldText/newText），
 * 优先于内置 {@code toString()} 规则。
 */
class ReflectionDiffEngineFormatterTest {

    @Test
    void registeredFormatterOverridesDefaultTextRendering() {
        CompareHandlerRegistry registry = CompareHandlerRegistry.withBuiltins();
        registry.registerFormatter(new ValueFormatter<BigDecimal>() {
            @Override
            public Class<BigDecimal> type() {
                return BigDecimal.class;
            }

            @Override
            public String format(BigDecimal value) {
                return "¥" + value.toPlainString();
            }
        });
        registry.freeze();
        ReflectionDiffEngine engine = new ReflectionDiffEngine(registry);

        OrderBean before = new OrderBean();
        before.setAmount(new BigDecimal("100.00"));
        OrderBean after = new OrderBean();
        after.setAmount(new BigDecimal("120.00"));

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).oldText()).isEqualTo("¥100.00");
        assertThat(result.changes().get(0).newText()).isEqualTo("¥120.00");
    }

    @Test
    void withoutRegisteredFormatterFallsBackToDefaultToString() {
        ReflectionDiffEngine engine = new ReflectionDiffEngine(CompareHandlerRegistry.withBuiltins());

        OrderBean before = new OrderBean();
        before.setAmount(new BigDecimal("100.00"));
        OrderBean after = new OrderBean();
        after.setAmount(new BigDecimal("120.00"));

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.changes().get(0).oldText()).isEqualTo("100.00");
        assertThat(result.changes().get(0).newText()).isEqualTo("120.00");
    }
}
