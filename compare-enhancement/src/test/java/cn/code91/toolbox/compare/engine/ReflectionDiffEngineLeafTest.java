package cn.code91.toolbox.compare.engine;

import cn.code91.facility.result.Result;
import cn.code91.toolbox.compare.core.ChangeKind;
import cn.code91.toolbox.compare.core.CompareError;
import cn.code91.toolbox.compare.core.DiffResult;
import cn.code91.toolbox.compare.core.TypeMismatch;
import cn.code91.toolbox.compare.spi.CompareHandlerRegistry;
import cn.code91.toolbox.compare.testfixtures.DateFieldBean;
import cn.code91.toolbox.compare.testfixtures.OrderBean;
import cn.code91.toolbox.compare.testfixtures.OrderStatus;
import cn.code91.toolbox.compare.testfixtures.PaymentStage;
import cn.code91.toolbox.compare.testfixtures.UuidFieldBean;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 叶子类型对比矩阵：基本类型、String、枚举、BigDecimal（compareTo 判等）、
 * LocalDate/LocalDateTime/OffsetDateTime。
 */
class ReflectionDiffEngineLeafTest {

    private final ReflectionDiffEngine engine = new ReflectionDiffEngine(CompareHandlerRegistry.withBuiltins());

    @Test
    void identicalObjectsProduceNoChanges() {
        OrderBean before = baseOrder();
        OrderBean after = baseOrder();

        Result<DiffResult, CompareError> result = engine.diff(before, after);

        assertThat(result.isOk()).isTrue();
        assertThat(result.get().identical()).isTrue();
    }

    @Test
    void bigDecimalDifferentScaleButEqualValueIsNotAChange() {
        OrderBean before = baseOrder();
        before.setAmount(new BigDecimal("1.0"));
        OrderBean after = baseOrder();
        after.setAmount(new BigDecimal("1.00"));

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.identical())
                .as("1.0 与 1.00 应视为相等（BigDecimal compareTo 判等）")
                .isTrue();
    }

    @Test
    void bigDecimalActualValueChangeIsDetected() {
        OrderBean before = baseOrder();
        before.setAmount(new BigDecimal("100.00"));
        OrderBean after = baseOrder();
        after.setAmount(new BigDecimal("120.00"));

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.changes()).hasSize(1);
        var change = result.changes().get(0);
        assertThat(change.path()).isEqualTo("amount");
        assertThat(change.kind()).isEqualTo(ChangeKind.MODIFIED);
        assertThat(change.oldValue()).isEqualTo(new BigDecimal("100.00"));
        assertThat(change.newValue()).isEqualTo(new BigDecimal("120.00"));
    }

    @Test
    void stringChangeIsDetected() {
        OrderBean before = baseOrder();
        before.setRemark("旧备注");
        OrderBean after = baseOrder();
        after.setRemark("新备注");

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.changes()).extracting("path").containsExactly("remark");
        assertThat(result.changes().get(0).oldText()).isEqualTo("旧备注");
        assertThat(result.changes().get(0).newText()).isEqualTo("新备注");
    }

    @Test
    void enumChangeIsDetected() {
        OrderBean before = baseOrder();
        before.setStatus(OrderStatus.DRAFT);
        OrderBean after = baseOrder();
        after.setStatus(OrderStatus.CONFIRMED);

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).path()).isEqualTo("status");
        assertThat(result.changes().get(0).oldText()).isEqualTo("DRAFT");
        assertThat(result.changes().get(0).newText()).isEqualTo("CONFIRMED");
    }

    @Test
    void localDateChangeIsDetected() {
        OrderBean before = baseOrder();
        before.setOrderDate(LocalDate.of(2026, 1, 1));
        OrderBean after = baseOrder();
        after.setOrderDate(LocalDate.of(2026, 7, 6));

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).path()).isEqualTo("orderDate");
    }

    @Test
    void localDateTimeChangeIsDetected() {
        OrderBean before = baseOrder();
        before.setCreatedAt(LocalDateTime.of(2026, 1, 1, 8, 0));
        OrderBean after = baseOrder();
        after.setCreatedAt(LocalDateTime.of(2026, 1, 1, 9, 30));

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).path()).isEqualTo("createdAt");
    }

    @Test
    void offsetDateTimeChangeIsDetected() {
        OrderBean before = baseOrder();
        before.setConfirmedAt(OffsetDateTime.of(2026, 1, 1, 8, 0, 0, 0, ZoneOffset.UTC));
        OrderBean after = baseOrder();
        after.setConfirmedAt(OffsetDateTime.of(2026, 1, 1, 8, 0, 0, 0, ZoneOffset.ofHours(8)));

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.changes()).hasSize(1);
        assertThat(result.changes().get(0).path()).isEqualTo("confirmedAt");
    }

    @Test
    void ignoredFieldNeverProducesChange() {
        OrderBean before = baseOrder();
        before.setId(1L);
        OrderBean after = baseOrder();
        after.setId(2L);

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.identical()).as("@CompareIgnore 字段不应参与对比").isTrue();
    }

    @Test
    void compareWithAnnotationOverridesEvenActualDifference() {
        OrderBean before = baseOrder();
        before.setCode("A");
        OrderBean after = baseOrder();
        after.setCode("B");

        DiffResult result = engine.diff(before, after).get();

        assertThat(result.identical())
                .as("@CompareWith(AlwaysEqualComparator) 应压制字段级差异，优先级最高")
                .isTrue();
    }

    @Test
    void dateFieldComparesAsLeaf() {
        DateFieldBean before = new DateFieldBean();
        before.setCreatedAt(new Date(1000L));
        DateFieldBean after = new DateFieldBean();
        after.setCreatedAt(new Date(2000L));

        Result<DiffResult, CompareError> result = engine.diff(before, after);

        assertThat(result.isOk()).as("P2 扩表后 Date 是叶子类型，不再走对象图反射").isTrue();
        assertThat(result.get().changes()).hasSize(1);
        var change = result.get().changes().get(0);
        assertThat(change.path()).isEqualTo("createdAt");
        assertThat(change.kind()).isEqualTo(ChangeKind.MODIFIED);
        assertThat(change.oldText())
                .as("Date 展示文本应经 DateUtil 按 datePattern 渲染（yyyy-MM-dd HH:mm:ss）")
                .matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    void equalDatesProduceNoChanges() {
        DateFieldBean before = new DateFieldBean();
        before.setCreatedAt(new Date(1000L));
        DateFieldBean after = new DateFieldBean();
        after.setCreatedAt(new Date(1000L));

        Result<DiffResult, CompareError> result = engine.diff(before, after);

        assertThat(result.isOk()).isTrue();
        assertThat(result.get().identical()).isTrue();
    }

    @Test
    void uuidFieldComparesAsLeaf() {
        UuidFieldBean before = new UuidFieldBean();
        before.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        UuidFieldBean after = new UuidFieldBean();
        after.setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));

        Result<DiffResult, CompareError> result = engine.diff(before, after);

        assertThat(result.isOk()).as("P2 扩表后 UUID 是叶子类型").isTrue();
        assertThat(result.get().changes()).hasSize(1);
        var change = result.get().changes().get(0);
        assertThat(change.path()).isEqualTo("id");
        assertThat(change.oldText()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(change.newText()).isEqualTo("22222222-2222-2222-2222-222222222222");
    }

    @Test
    void enumConstantsWithBodiesCompareAsLeafByName() {
        Result<DiffResult, CompareError> result = engine.diff(PaymentStage.AUTHORIZED, PaymentStage.CAPTURED);

        assertThat(result.isOk())
                .as("带方法体枚举常量（运行时类 PaymentStage$1/$2）不应被误判为 TypeMismatch")
                .isTrue();
        assertThat(result.get().changes()).hasSize(1);
        var change = result.get().changes().get(0);
        assertThat(change.kind()).isEqualTo(ChangeKind.MODIFIED);
        assertThat(change.oldText()).as("展示文本取 name() 而非被覆写的 toString()").isEqualTo("AUTHORIZED");
        assertThat(change.newText()).isEqualTo("CAPTURED");
    }

    @Test
    void sameEnumConstantWithBodyIsIdentical() {
        Result<DiffResult, CompareError> result = engine.diff(PaymentStage.AUTHORIZED, PaymentStage.AUTHORIZED);

        assertThat(result.isOk()).isTrue();
        assertThat(result.get().identical()).isTrue();
    }

    @Test
    void differentEnumTypesRemainTypeMismatch() {
        Result<DiffResult, CompareError> result = engine.diff((Object) OrderStatus.DRAFT, (Object) PaymentStage.AUTHORIZED);

        assertThat(result.isErr()).as("不同声明枚举之间仍是类型不一致").isTrue();
        assertThat(result.getErr()).isInstanceOf(TypeMismatch.class);
    }

    private static OrderBean baseOrder() {
        OrderBean order = new OrderBean();
        order.setId(1L);
        order.setAmount(new BigDecimal("100.00"));
        order.setRemark("备注");
        order.setStatus(OrderStatus.DRAFT);
        order.setOrderDate(LocalDate.of(2026, 1, 1));
        order.setCreatedAt(LocalDateTime.of(2026, 1, 1, 8, 0));
        order.setConfirmedAt(OffsetDateTime.of(2026, 1, 1, 8, 0, 0, 0, ZoneOffset.UTC));
        order.setCode("SAME");
        return order;
    }
}
