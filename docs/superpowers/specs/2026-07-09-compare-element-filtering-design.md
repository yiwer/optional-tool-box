# compare 元素级 include/exclude 过滤 — 设计裁定（P3）

日期：2026-07-09。来源：P2 backlog 清障整分支终审 Important 的"正解"项（P2 仅做了披露 + 回归钉）。三项裁定经用户拍板。

## 1. 背景与实证事实（P2 终审/修复轮结论，勿重新调查）

- include/exclude 过滤目前唯一咨询点在 `ReflectionDiffEngine.Traversal.compareField`（字段边界），用完整字段路径。
- **同尺寸结构体元素不泄漏**：其内部字段带完整路径（`items[1].price`）重新经过字段门，被正确过滤。
- 两类元素级产出**绕过过滤**（P2 回归钉钉住现状）：
  1. 叶子类型集合/Map 元素——`compareIndexedCollection`/`compareMap` 直接递归 `compare()` → `compareLeaf` 产出（钉：`includeDoesNotFilterLeafTypedSiblingElements`，`tags[b]` MODIFIED 泄漏）；
  2. 尺寸/键集不匹配的 ADDED/REMOVED——容器方法内直接 `changes.add`（钉：`includeDoesNotFilterSizeMismatchAddedElements`，`items[1]` ADDED 泄漏）。
- exclude 元素路径（`items[1]`）在存量实现中从不生效，但 `DiffOptions.matchesPrefix` 特意支持 `prefix[` 分隔——契约暗示元素路径本应可寻址。
- `isPathIncluded` 的 include 祖先放行已支持 `[` 分隔（`isAncestorOf`）——元素层加门后组合语义自动成立。

## 2. 裁定（用户拍板，2026-07-09）

| # | 裁定 | 内容 |
|---|---|---|
| R1 | **统一元素门** | `compareMap`/`compareIndexedCollection` 对每个元素（递归前 + 记录 ADDED/REMOVED 前）都咨询 `options.isPathIncluded(elementPath)`。语义与字段层对称；被过滤的结构体元素直接剪枝不下钻。否决备选"只堵已知泄漏面"（两套机制并存、结构体元素白跑反射）。 |
| R2 | **增删一并隐藏** | 过滤语义完全统一：命中即不产出，不区分 MODIFIED/ADDED/REMOVED。exclude `{"items[1]"}` 且列表 1→2 增长时"新增 items[1]"记录被滤掉。与字段层 exclude 行为对称。否决"增删例外保留"（语义不对称、规则复杂化）。 |
| R3 | **过滤即不看** | 被过滤元素不下钻，其内部结构错误（CycleDetected/DepthExceeded/FieldAccessError）不再被发现——同一对象今天 diff 报 Err，加 exclude 后可变 Ok。与字段层既有语义对齐（被排除字段的 FaultyBean 今天就不报错）。否决"仍下钻验结构但丢弃记录"（白付遍历成本、与字段层不一致）。 |

## 3. 语义规格

`isPathIncluded(path)` 成为**全路径统一契约**：字段路径与元素路径（`items[0]`、`tags[key]`）同权。

- **exclude 命中元素**：该元素零产出（含 ADDED/REMOVED）、不下钻。
- **include 约束下**：元素路径按"自身/后代/祖先"三态判定（谓词现有逻辑，零改动）——include `"items[0].price"` 时：`items`（祖先）放行下钻 → `items[0]`（祖先）放行下钻 → `items[0].price` 命中产出 → `items[1]`（无关）剪枝零产出。
- **exclude 优先于 include** 的既有规则不变（`excludeTakesPrecedenceOverInclude` 已钉）。
- 环检测登记（`enterGraphNode`）仍对容器本身进行——容器自引用照常报 `CycleDetected`，仅"被过滤元素内部"的错误不再暴露。

## 4. 实现形态

唯一改动文件：`compare-enhancement/src/main/java/cn/code91/toolbox/compare/engine/ReflectionDiffEngine.java`。

`Traversal` 内 6 个元素产出/递归点前加同一道门（`if (!options.isPathIncluded(elementPath)) { continue/return; }` 形态视各点结构）：

1. `compareMap` REMOVED 循环（old 有 new 无）；
2. `compareMap` ADDED 分支（new 有 old 无）；
3. `compareMap` 共同键递归 `compare(elementPath, ...)`；
4. `compareIndexedCollection` 共同下标递归；
5. `compareIndexedCollection` REMOVED 尾循环；
6. `compareIndexedCollection` ADDED 尾循环。

不新增类、不改 `DiffOptions`（谓词已就绪）、不改任何公开签名、不动 `compareField`。

## 5. 已知局限（披露不处理）

Map key 的 `toString()` 含 `.` 或 `[` 时，拼出的元素路径会使前缀匹配错位——属存量路径编码语义（路径本就是字符串拼接，P1 起如此），在 `DiffOptions.isPathIncluded` Javadoc 披露，不做转义/寻址方案。

## 6. 文档与测试面

**文档：**
- `DiffOptions.isPathIncluded` Javadoc：删除 P2 的"另一已知边界"段（边界闭合），改为正面陈述元素级过滤语义（R1/R2/R3）+ §5 局限；
- `docs/design/01-compare-enhancement.md` §4.3 补一条遍历规则（include/exclude 作用于字段与元素两层）。

**测试（`ReflectionDiffEnginePathFilterTest` 为主）：**
- 两个 P2 回归钉**按其注释预告显式翻转**（泄漏→过滤），更名为正面语义（如 `includeFiltersSizeMismatchAddedElements`/`includeFiltersLeafTypedSiblingElements`）；
- 新增矩阵：include 元素命中（叶子列表 + Map 各一）、exclude 元素 MODIFIED 被滤、exclude 隐藏 ADDED（列表增长，R2 钉）、被 exclude 元素含环 → diff Ok（R3 错误面收窄钉）、include 深路径穿集合端到端（`items[0].price` 组合，§3 三态）；
- 约 7-8 个新用例 + 2 个翻转；jacoco 新分支覆盖由上述矩阵天然满足。

## 7. 风险与兼容性

行为变化仅在"配置了元素路径 include/exclude"时可见：此前该配置要么静默无效（exclude 元素）要么泄漏（include 穿集合），不存在依赖旧行为的合理用法。两个 P2 钉的翻转即变化的显式记录。规模：单任务一轮 TDD，无跨模块影响，无依赖/装配/ArchUnit 触碰。

## 8. 范围外

- Map key 转义/元素寻址 DSL（§5 局限的"根治"）——无业务牵引不做；
- 集合三策略（BY_KEY/AS_SET/@CompareKey）——独立 roadmap 项；
- Month/DayOfWeek 渲染优先级 pin——与本项无关的独立小项。
