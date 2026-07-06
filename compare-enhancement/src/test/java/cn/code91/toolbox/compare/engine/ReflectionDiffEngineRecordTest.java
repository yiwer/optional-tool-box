package cn.code91.toolbox.compare.engine;

import cn.code91.toolbox.compare.core.ChangeKind;
import cn.code91.toolbox.compare.core.DiffResult;
import cn.code91.toolbox.compare.spi.CompareHandlerRegistry;
import cn.code91.toolbox.compare.testfixtures.OrderRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * record 支持：accessor（非 getter）取值路径必须与 JavaBean 行为一致。
 */
class ReflectionDiffEngineRecordTest {

    private final ReflectionDiffEngine engine = new ReflectionDiffEngine(CompareHandlerRegistry.withBuiltins());

    @Test
    void recordFieldChangeIsDetected() {
        OrderRecord before = new OrderRecord(1L, new BigDecimal("100.00"), "旧备注");
        OrderRecord after = new OrderRecord(1L, new BigDecimal("120.00"), "旧备注");

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).path()).isEqualTo("amount");
        assertThat(result.changes().get(0).kind()).isEqualTo(ChangeKind.MODIFIED);
        assertThat(result.changes().get(0).label()).isEqualTo("订单金额");
    }

    @Test
    void recordIgnoredFieldNeverProducesChange() {
        OrderRecord before = new OrderRecord(1L, new BigDecimal("100.00"), "备注");
        OrderRecord after = new OrderRecord(2L, new BigDecimal("100.00"), "备注");

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.identical()).as("record 上的 @CompareIgnore 同样应生效").isTrue();
    }

    @Test
    void recordIdenticalValuesProduceNoChanges() {
        OrderRecord before = new OrderRecord(1L, new BigDecimal("100.00"), "备注");
        OrderRecord after = new OrderRecord(1L, new BigDecimal("100.00"), "备注");

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.identical()).isTrue();
    }
}
