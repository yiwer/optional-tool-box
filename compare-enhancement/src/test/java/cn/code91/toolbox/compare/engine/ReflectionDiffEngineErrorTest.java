package cn.code91.toolbox.compare.engine;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.compare.core.CompareError;
import cn.code91.toolbox.compare.core.CycleDetected;
import cn.code91.toolbox.compare.core.DepthExceeded;
import cn.code91.toolbox.compare.core.DiffOptions;
import cn.code91.toolbox.compare.core.DiffResult;
import cn.code91.toolbox.compare.core.FieldAccessError;
import cn.code91.toolbox.compare.core.TypeMismatch;
import cn.code91.toolbox.compare.spi.CompareHandlerRegistry;
import cn.code91.toolbox.compare.testfixtures.ChainBean;
import cn.code91.toolbox.compare.testfixtures.FaultyBean;
import cn.code91.toolbox.compare.testfixtures.NoteBean;
import cn.code91.toolbox.compare.testfixtures.SelfRefBean;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构性错误：环检测、深度超限、类型不一致；以及 {@code nullAsEmpty} 两态语义。
 */
class ReflectionDiffEngineErrorTest {

    private final ReflectionDiffEngine engine = new ReflectionDiffEngine(CompareHandlerRegistry.withBuiltins());

    @Test
    void cycleInOldObjectGraphIsDetected() {
        SelfRefBean before = new SelfRefBean();
        before.setName("root");
        before.setNext(before); // 自引用成环

        SelfRefBean after = new SelfRefBean();
        after.setName("root");
        after.setNext(new SelfRefBean());

        Result<DiffResult, CompareError> result = engine.diff(before, after);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(CycleDetected.class);
        assertThat(result.getErr().path()).isEqualTo("next");
    }

    @Test
    void depthExceededWhenChainLongerThanMaxDepth() {
        ChainBean before = buildChain(10);
        ChainBean after = buildChain(10);
        // 修改最深处的值，确保引擎必须递归到底才能发现无变更——用以逼出深度检测。
        DiffOptions options = new DiffOptions(3, false, Set.of(), Set.of());

        Result<DiffResult, CompareError> result = engine.diff(before, after, options);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(DepthExceeded.class);
    }

    @Test
    void depthWithinLimitSucceeds() {
        ChainBean before = buildChain(2);
        ChainBean after = buildChain(2);
        DiffOptions options = new DiffOptions(8, false, Set.of(), Set.of());

        Result<DiffResult, CompareError> result = engine.diff(before, after, options);

        assertThat(result.isOk()).isTrue();
        assertThat(result.get().identical()).isTrue();
    }

    @Test
    void typeMismatchWhenRuntimeTypesDiffer() {
        Object before = "a string";
        Object after = new BigDecimal("1.00");

        Result<DiffResult, CompareError> result = engine.diff(before, after);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(TypeMismatch.class);
    }

    @Test
    void nullAsEmptyFalseTreatsNullAndBlankAsDifferent() {
        NoteBean before = new NoteBean();
        before.setNote(null);
        NoteBean after = new NoteBean();
        after.setNote("");
        DiffOptions options = new DiffOptions(8, false, Set.of(), Set.of());

        DiffResult result = engine.diff(before, after, options).get();

        assertThat(result.identical()).as("nullAsEmpty=false 时 null 与空串应视为不同").isFalse();
    }

    @Test
    void nullAsEmptyTrueTreatsNullAndBlankAsEqual() {
        NoteBean before = new NoteBean();
        before.setNote(null);
        NoteBean after = new NoteBean();
        after.setNote("");
        DiffOptions options = new DiffOptions(8, true, Set.of(), Set.of());

        DiffResult result = engine.diff(before, after, options).get();

        assertThat(result.identical()).as("nullAsEmpty=true 时 null 与空串应视为相等").isTrue();
    }

    @Test
    void fieldAccessErrorWhenGetterThrows() {
        FaultyBean before = new FaultyBean();
        FaultyBean after = new FaultyBean();

        Result<DiffResult, CompareError> result = engine.diff(before, after);

        assertThat(result.isErr()).isTrue();
        assertThat(result.getErr()).isInstanceOf(FieldAccessError.class);
        assertThat(result.getErr().path()).isEqualTo("risky");
    }

    private static ChainBean buildChain(int length) {
        ChainBean head = new ChainBean();
        head.setValue("v0");
        ChainBean current = head;
        for (int i = 1; i <= length; i++) {
            ChainBean next = new ChainBean();
            next.setValue("v" + i);
            current.setChild(next);
            current = next;
        }
        return head;
    }
}
