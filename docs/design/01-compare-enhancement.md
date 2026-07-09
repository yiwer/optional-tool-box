# compare-enhancement 设计文档

> 状态：设计稿 v0.1｜配置前缀 `toolbox.compare.*`｜基础包 `cn.code91.toolbox.compare`

## 1. 模块定位

**注解驱动的对象/集合差异引擎**：给两个 Java 对象（或两组数据），产出结构化的"改了什么"清单，并能渲染成人类可读文案或可入库的 JSON。

- **解决什么**：操作审计（"张三把订单金额从 100 改成 120"）、审批前后对比展示、两批数据的对账核对（added/removed/changed）。
- **不做什么**：不做审计数据的存储与查询（那是 JaVers 式的重仓库，交给业务表）；不做文本/文件 diff（行级 diff 是另一域）；不做数据库快照抓取（before 值由调用方提供）。

## 2. 领域调研

### 2.1 需求场景盘点

| 场景 | 输入 | 期望输出 |
|---|---|---|
| 操作日志 | 更新前后的实体 | 字段级变更清单，带中文标签与格式化值，可存审计表 |
| 审批对比 | 草稿 vs 生效版本（含嵌套对象、明细行集合） | 树状/扁平路径的变更，集合元素按业务键配对 |
| 数据对账 | 两个集合（本方 vs 对方账单） | 新增/缺失/配对后字段不一致三类结果 |
| 幂等判断 | 新请求 vs 已存对象 | 是否完全一致（快速判等） |

### 2.2 业界方案对比

| 方案 | 优点 | 缺点 | 结论 |
|---|---|---|---|
| **JaVers** | 成熟、支持审计存储、Shadow 查询 | 重：自带 repository/快照存储概念，依赖面大，与"轻量可选件"定位冲突 | 不引入 |
| **java-object-diff**（de.danielbechler） | 经典的对象图 diff | 2016 年后停更，无 JPMS/新 JDK 保障 | 不引入 |
| **Apache Commons `DiffBuilder`** | 轻 | 全手写字段登记，无注解/反射驱动，样板代码多 | 不引入 |
| **自研注解驱动引擎** | 零第三方依赖；注解语义可完全对齐业务（中文标签、i18n、业务键）；可深度复用 facility（DateUtil/LocaleUtil/Result） | 需要自己处理对象图遍历的边界（环、深度、集合策略） | **采用** |

自研的风险主要在对象图遍历的正确性，通过硬性规则收敛：深度上限、环检测（IdentityHashMap 记录访问路径）、集合三策略显式化。这些在 §4 中逐一固化。

## 3. 与 server-facility 的关系

| 复用 | 用途 |
|---|---|
| `Result<T,E>` | `diff()` 返回 `Result<DiffResult, CompareError>` |
| `DateUtil` | 日期/时间值的默认渲染格式 |
| `LocaleUtil` + `AggregatedMessageSource` | `@CompareLabel(messageKey=…)` 的多语言标签解析 |
| `Hashing`（P2） | 大对象/大集合的快速预判等（哈希不同才做逐字段 diff） |

无任何第三方依赖 → 本模块是五个模块里唯一"引入即全量可用、无 L1 条件"的模块。

## 4. 核心抽象设计

### 4.1 注解（`cn.code91.toolbox.compare.annotation`）

```java
@CompareLabel(value = "订单金额", messageKey = "order.amount")  // 字段展示名；messageKey 优先，走 i18n
@CompareIgnore                                                   // 不参与对比（如 updatedAt、version）
@CompareKey                                                      // 集合元素的业务身份键（对账/明细行配对）
@CompareWith(MoneyComparator.class)                              // 字段级自定义比较器
```

未加注解的字段默认参与对比，标签回退为字段名——**默认可用，注解只做增强**。

### 4.2 Seam 接口与值对象（`core`）

```java
public interface DiffEngine {
    <T> Result<DiffResult, CompareError> diff(T oldValue, T newValue);
    <T> Result<DiffResult, CompareError> diff(T oldValue, T newValue, DiffOptions options);
}

public record DiffResult(List<FieldChange> changes) {
    public boolean identical();
    public String render(ChangeRenderer renderer);   // 委托渲染器
}

/** path 形如 "amount"、"address.city"、"items[sku=A01].price" */
public record FieldChange(String path, String label, ChangeKind kind,
                          Object oldValue, Object newValue,
                          String oldText, String newText) {}     // *Text 为已格式化展示值

public enum ChangeKind { ADDED, REMOVED, MODIFIED }

public sealed interface CompareError
        permits TypeMismatch, DepthExceeded, CycleDetected, FieldAccessError, RenderError { … }
```

`DiffOptions`（record，全部有默认值）：`maxDepth`（默认 8）、`nullAsEmpty`（null 与 "" 是否视为相等，默认 false）、`includePaths/excludePaths`、`collectionStrategy` 覆盖。

### 4.3 对象图遍历规则（正确性硬约束）

1. **叶子类型**直接比：基本类型/包装、String、枚举（渲染取 `@CompareLabel` 或 name）、日期时间（`TemporalAccessor` 与 `java.util.Date` 含 `java.sql` 子类，均 `DateUtil` 格式化；Date 为 P2 扩表）、`UUID`（P2 扩表）、`BigDecimal`（**`compareTo` 判等**，规避 scale 陷阱）。
2. **嵌套对象**递归展开，路径用 `.` 连接；超过 `maxDepth` 返回 `DepthExceeded`。
3. **环检测**：以 IdentityHashMap 记录当前递归栈上的对象，命中即返回 `CycleDetected`（错误而非死循环）。
4. **集合三策略**：
   - `BY_KEY`（元素类含 `@CompareKey` 时自动选用）：按键配对 → 产出 ADDED/REMOVED/逐字段 MODIFIED，路径 `items[key=X].field`；
   - `BY_INDEX`：按下标逐位比（适合固定顺序小列表）；
   - `AS_SET`：只报 ADDED/REMOVED（适合标签集）。
5. **Map**：按 key 配对，等价于 `BY_KEY`。
6. 字段读取失败（反射异常）→ `FieldAccessError`，带字段路径。

### 4.4 SPI（`spi`）

```java
public interface ValueComparator<T>  { Class<T> type(); boolean isEqual(T a, T b); }
public interface ValueFormatter<T>   { Class<T> type(); String format(T value); }
public interface ChangeRenderer      { String render(DiffResult result); }   // 内置：PlainTextRenderer(中文模板)、JsonRenderer(入库)
public interface CompareRegistryCustomizer { void customize(CompareHandlerRegistry registry); }
```

`CompareHandlerRegistry` 按 §总体设计 3.4 模式装配：内置 comparator/formatter → `ObjectProvider` 收集应用自定义 → `freeze()`。查找顺序：字段级 `@CompareWith` > 应用注册的类型级 > 内置 > 兜底 `Objects.equals`。

### 4.5 集合核对 API（对账场景，P3）

```java
public final class CollectionDiffs {
    public static <T, K> Result<ReconcileResult<T>, CompareError>
        byKey(Collection<T> left, Collection<T> right, Function<T, K> keyFn);
}
public record ReconcileResult<T>(List<T> onlyLeft, List<T> onlyRight, List<ChangedPair<T>> changed) {}
```

对账是"集合层"的 diff，复用同一套字段级引擎处理 `changed` 对。

## 5. 自动装配设计

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "toolbox.compare", name = "enabled",
                       havingValue = "true", matchIfMissing = true)      // L2
@EnableConfigurationProperties(ToolboxCompareProperties.class)
public class ToolboxCompareAutoConfiguration {

    @Bean @ConditionalOnMissingBean                                       // L4 Seam
    CompareHandlerRegistry compareHandlerRegistry(
            ObjectProvider<ValueComparator<?>> comparators,
            ObjectProvider<ValueFormatter<?>> formatters,
            ObjectProvider<CompareRegistryCustomizer> customizers) { …收集+freeze… }

    @Bean @ConditionalOnMissingBean                                       // L4 Seam
    DiffEngine diffEngine(CompareHandlerRegistry registry, ToolboxCompareProperties props) { … }

    @Bean("toolboxCompareMessageSource")
    @ConditionalOnMissingBean(name = "toolboxCompareMessageSource")       // 参与 facility i18n 聚合
    MessageSource toolboxCompareMessageSource() { … }
}
```

无 L1 条件（零第三方依赖）、无 L3 选型（无 provider 概念）。反射元数据（字段列表、注解、getter）按类缓存于 `ClassMetaCache`（ConcurrentHashMap，类维度只解析一次）。

## 6. 配置设计

```yaml
toolbox:
  compare:
    enabled: true            # 总开关，默认 true
    max-depth: 8             # 对象图递归深度上限
    null-as-empty: false     # null 与空串是否视为相等
    date-pattern: "yyyy-MM-dd HH:mm:ss"   # 日期默认渲染格式（缺省跟随 facility DateUtil）
    render:
      modified-template: "{label}：由「{old}」改为「{new}」"   # PlainTextRenderer 模板
      added-template:    "{label}：新增「{new}」"
      removed-template:  "{label}：移除「{old}」"
```

## 7. 依赖策略

| 依赖 | scope | 说明 |
|---|---|---|
| `cn.code91:server-facility` | compile | Result/DateUtil/LocaleUtil |
| `org.springframework.boot:spring-boot-autoconfigure` | compile | 装配 |
| （无第三方运行时依赖） | — | 本模块卖点之一 |
| ArchUnit / JUnit / AssertJ | test | — |

## 8. 使用视角（消费方）

```java
// 1. 实体标注
public class Order {
    @CompareIgnore private Long id;
    @CompareLabel("订单金额") private BigDecimal amount;
    @CompareLabel("收货地址") private Address address;          // 嵌套自动展开
    @CompareLabel("明细") private List<OrderItem> items;        // OrderItem 内有 @CompareKey
}

// 2. 注入使用
Result<DiffResult, CompareError> r = diffEngine.diff(before, after);
r.map(d -> auditService.save(order.getId(), d.render(jsonRenderer)));   // 入库
String text = r.orElseThrow().render(plainTextRenderer);                 // "订单金额：由「100」改为「120」"
```

## 9. 测试策略

- 纯单测矩阵：叶子类型表（含 BigDecimal scale、枚举、各日期类型）、嵌套、三种集合策略、环、深度超限、null 语义、`@CompareWith` 优先级；
- 装配测试：`ApplicationContextRunner` 验证 enabled 开关、Seam 覆盖、SPI 收集顺序；
- 性能护栏测试：1 万元素 `BY_KEY` 集合 diff 的耗时断言（防止实现退化为 O(n²)——键配对必须走 HashMap）。

## 10. 演进路线

| 阶段 | 内容 |
|---|---|
| P1 | 扁平/嵌套对象 diff + 注解体系 + PlainText/Json 渲染器 + 装配 |
| P2 | 集合三策略完整实现；`Hashing` 快速预判等；i18n messageKey 标签 |
| P3 | `CollectionDiffs` 对账 API；`ReconcileResult` 报表友好输出 |
| P4（观望） | 与 database-enhancement 审计填充联动的"自动操作日志"注解（`@AuditDiff`），跨模块特性，需两模块都稳定后再议 |

**风险**：反射遍历对 record/不可变类型的取值路径与普通 JavaBean 不同（record 用 accessor 而非 getter），P1 必须同时覆盖两种类型的测试。
