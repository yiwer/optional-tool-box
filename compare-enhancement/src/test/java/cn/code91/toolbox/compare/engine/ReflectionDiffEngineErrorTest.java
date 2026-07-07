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
import cn.code91.toolbox.compare.testfixtures.DateFieldBean;
import cn.code91.toolbox.compare.testfixtures.FaultyBean;
import cn.code91.toolbox.compare.testfixtures.NoteBean;
import cn.code91.toolbox.compare.testfixtures.SelfRefBean;
import cn.code91.toolbox.compare.testfixtures.SetFieldBean;
import cn.code91.toolbox.compare.testfixtures.UuidFieldBean;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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

    @Test
    void selfReferencingListReturnsCycleDetectedInsteadOfStackOverflow() {
        List<Object> before = new ArrayList<>();
        before.add(before); // List 直接包含自身
        List<Object> after = new ArrayList<>();
        after.add(after);

        Result<DiffResult, CompareError> result = engine.diff(before, after);

        assertThat(result.isErr()).as("纯集合自引用必须收敛为 CycleDetected，而非 StackOverflowError").isTrue();
        assertThat(result.getErr()).isInstanceOf(CycleDetected.class);
    }

    @Test
    void selfReferencingMapReturnsCycleDetectedInsteadOfStackOverflow() {
        Map<String, Object> before = new HashMap<>();
        before.put("self", before); // Map value 引用回该 Map 自身
        Map<String, Object> after = new HashMap<>();
        after.put("self", after);

        Result<DiffResult, CompareError> result = engine.diff(before, after);

        assertThat(result.isErr()).as("Map 自引用必须收敛为 CycleDetected，而非 StackOverflowError").isTrue();
        assertThat(result.getErr()).isInstanceOf(CycleDetected.class);
    }

    @Test
    void collectionOnlyDeepNestingExceedsMaxDepth() {
        // 仅由 List 嵌套构成的深结构（无普通对象边），深度检测必须同样生效。
        Object before = nestedList(6, "old");
        Object after = nestedList(6, "new");
        DiffOptions options = new DiffOptions(3, false, Set.of(), Set.of());

        Result<DiffResult, CompareError> result = engine.diff(before, after, options);

        assertThat(result.isErr()).as("纯集合嵌套超过 maxDepth 应返回 DepthExceeded，而非静默递归到底").isTrue();
        assertThat(result.getErr()).isInstanceOf(DepthExceeded.class);
    }

    private static Object nestedList(int levels, String leaf) {
        Object current = leaf;
        for (int i = 0; i < levels; i++) {
            current = List.of(current);
        }
        return current;
    }

    /**
     * I2 回归：非 List 的 Collection（Set/Queue 等）P1 不支持，遇到时应返回显式 Err
     * （消息说明"仅支持 List/数组/Map，请转换或 @CompareIgnore"），而非让反射钻入
     * {@code HashSet} 内部字段时抛出的 {@code InaccessibleObjectException} 未受检穿透。
     */
    @Test
    void setFieldReturnsErrInsteadOfThrowing() {
        SetFieldBean before = new SetFieldBean();
        before.setTags(new HashSet<>(Set.of("a", "b")));
        SetFieldBean after = new SetFieldBean();
        after.setTags(new HashSet<>(Set.of("a", "c")));

        Result<DiffResult, CompareError> result = engine.diff(before, after);

        assertThat(result.isErr()).as("Set 字段应返回 Err 而非抛异常").isTrue();
        assertThat(result.getErr().path()).isEqualTo("tags");
    }

    /**
     * I2 回归：{@code java.util.Date} 不在叶子类型表内（P2 backlog，本次不扩表），
     * 引擎按普通对象递归展开其内部字段时会命中 JDK 模块边界反射限制；该失败必须
     * 转换为带路径的 Err，而非把 {@code InaccessibleObjectException} 直接抛给调用方。
     */
    @Test
    void dateFieldReturnsErrInsteadOfThrowing() {
        DateFieldBean before = new DateFieldBean();
        before.setCreatedAt(new Date(1000L));
        DateFieldBean after = new DateFieldBean();
        after.setCreatedAt(new Date(2000L));

        Result<DiffResult, CompareError> result = engine.diff(before, after);

        assertThat(result.isErr()).as("java.util.Date 字段应返回 Err 而非抛异常").isTrue();
        assertThat(result.getErr().path()).isEqualTo("createdAt");
    }

    /**
     * I2 回归：{@code UUID} 同样不在叶子类型表内，按普通对象展开时命中同一类反射限制。
     */
    @Test
    void uuidFieldReturnsErrInsteadOfThrowing() {
        UuidFieldBean before = new UuidFieldBean();
        before.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        UuidFieldBean after = new UuidFieldBean();
        after.setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));

        Result<DiffResult, CompareError> result = engine.diff(before, after);

        assertThat(result.isErr()).as("UUID 字段应返回 Err 而非抛异常").isTrue();
        assertThat(result.getErr().path()).isEqualTo("id");
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
