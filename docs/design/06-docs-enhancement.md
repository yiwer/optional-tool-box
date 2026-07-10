# docs-enhancement 设计文档

> 状态：设计稿 v0.1｜配置前缀 `toolbox.docs.*`｜基础包 `cn.code91.toolbox.docs`
> 无 beacon 参考实现（beacon-support 没有对应模块）——本模块**自研**，底座包裹业界事实标准 springdoc-openapi；主要价值在"自动装配 + 与 facility 契约（`BaseResponse`/`Result`）打通"，而非重造 OpenAPI 生成器。

## 1. 模块定位

**API 接口文档统一自动装配 Seam**：消费方应用引入本模块后，自动获得基于 OpenAPI 3 的接口文档能力（分组、Info/安全方案/服务器列表配置、环境门禁、导出），并且**对 facility 契约保持感知**——发现控制器方法误把内部 `Result<T,E>` 通道直接当返回类型暴露时启动期告警，`BaseResponse<T>`/`PageBaseResponse<T>` 包装泛型的文档 schema 命名做清理，错误响应有统一的通用形状文档。

> **技术澄清（写设计文档 v0.1 时的一处过度设想，写实现计划前已修正）**：最初设想"裸接 springdoc 时 `BaseResponse<T>` 的 `data` 字段泛型解析不出真实类型"——读了 facility 源码后确认这站不住脚：`BaseResponse<T>` 是普通 Lombok `@Data` 类（非 sealed/record），facility 也没有 `ResponseBodyAdvice` 静默包装，控制器方法本就要显式声明 `BaseResponse<T>` 为返回类型（见其 Javadoc 用法示例）。现代 springdoc/swagger-core 对这种"方法签名上的具体参数化泛型"本来就能正确解析出 `data: T`，不需要本模块介入。真正值得做的三件事见下方。

- **解决什么**：（1）**Result 通道泄漏**——没有任何机制阻止开发者把控制器方法的声明返回类型误写成内部服务层的 `Result<T,E>`（正确做法是先经 `BaseResponse.fromResult(...)` 转换），一旦发生，springdoc 会对着一个 sealed 接口生成混乱或失败的 schema；本模块在启动期扫描并告警，把偶发的契约破坏面暴露给开发者，而不是任由其在文档里悄悄裂开。（2）`BaseResponse<T>`/`PageBaseResponse<T>` 包装泛型的默认 schema 命名（如 `BaseResponseUserDto`）冗长，做命名修饰（非结构性修复）。（3）`XxxError` 系列目前没有数字 code，错误响应文档只能是通用形状，本模块把这个通用形状明确地文档化，而非放任 springdoc 按默认反射生成一个看不出错误语义的 schema。（4）分组声明分散在各处、命名风格与本仓库其余四模块（Registry + 逻辑名）不一致；（5）接口文档默认在生产环境也原样暴露，属于常见误配置风险点。
- **不做什么**：不做接口 Mock 服务（契约测试/mock 归独立工具）；不做多服务聚合网关（那是独立部署的门户，见"技术基座"选型讨论，本模块只服务单个消费方应用的进程内自动装配）；P1 不做按 endpoint 声明具体业务错误变体的枚举（见 §10 演进路线）。

## 2. 领域调研

### 2.1 需求场景盘点

| 场景 | 需要的能力 |
|---|---|
| 多团队/多域接口共存一个服务 | 按包名/路径把接口分组展示，互不干扰 |
| 文档基础信息与安全方案说明 | 标题/版本/联系人、认证方式（如 Bearer JWT）在文档 UI 里可见（仅文档展示，不代为鉴权） |
| 误把内部 `Result<T,E>` 暴露为控制器返回类型 | 启动期扫描告警，而非任由 springdoc 对 sealed 接口生成混乱 schema |
| 统一响应壳 schema 命名整洁 | `BaseResponse<T>`/`PageBaseResponse<T>` 包装泛型的默认冗长命名做清理 |
| 错误响应形状可读 | 明确文档化 `BaseResponse` 错误分支的通用形状（`code`/`message`/`description`） |
| 生产环境误暴露防护 | 默认不在生产 profile 暴露 `/v3/api-docs`、`/swagger-ui/**`，需显式打开 |
| 联调/归档 | 一键导出当前 spec 为 JSON/YAML/Postman collection |

### 2.2 技术基座选型

| 方向 | 结论 |
|---|---|
| springdoc-openapi 薄封装 | **采纳**。Spring Boot 3 官方推荐路径，生态成熟，`@Operation`/`@Schema`/`@Tag` 注解齐全；本模块只做自动装配 + facility 契约打通的增量 |
| 自研轻量文档模型 | 放弃。会重新发明 OpenAPI 生态已解决的问题（schema 生成、UI、校验注解映射），工作量大、价值存疑 |
| 多服务文档聚合网关 | 放弃（定位不符）。这是独立可部署服务的形态，与本仓库"消费方进程内自动装配 jar"的模式不符；不排除未来作为**另一个仓库**存在 |
| knife4j（UI 增强） | P2 可选皮肤：knife4j 自身以 springdoc 为底座，本模块 P1 不做深度集成，消费方可自行叠加其 starter |

### 2.3 已知限制与坦诚化

1. springdoc 原生已支持多分组（`springdoc.group-configs[]`），本模块的"分组"是**配置面翻译层**（`toolbox.docs.groups.*` → 前者），不是重新实现分组机制——避免"看起来造了轮子"的误解，需在 README/DESIGN.md 里显式说明。
2. facility 的各模块错误类型（`StorageError`/`MailError`/`LlmError`/`CompareError`）目前**没有数字 code**，只有 `message()`；因此 P1 的错误响应文档只能展示 `BaseResponse` 错误分支的**通用形状**（`code`/`message`/`description` 字段本身），不能按 endpoint 精确枚举"这个接口可能返回哪几种业务错误"——这需要跨模块错误码体系先成熟，列入 P2（§10）。
3. facility 未提供任何 Security/鉴权集成（无 `SecurityFilterChain`/JWT），本模块的"环境门禁"只做**暴露开关**（是否注册/暴露文档端点），不做**访问鉴权**（谁能看）；后者需要单独的 `DocsAccessGuard` SPI，P1 不做（见 §10）。
4. 本模块默认假设消费方是 Spring MVC（Servlet 栈），对齐 facility 现有 `BaseResponse`/`PageQuery` 等 Web 基建的技术栈；WebFlux 支持不在 P1 范围。

## 3. 与 server-facility 的关系

| 复用 | 用途 |
|---|---|
| `Result<T,E>` | `DocsExporter` 等本模块领域 API 的返回值 |
| `web.response.BaseResponse<T>` / `PageBaseResponse<T>` | **旗舰能力的识别目标**：本模块识别这两个类型做 schema 命名修饰，并识别控制器方法误把 `Result<T,E>` 当返回类型时告警（而非"展开泛型"——现代 springdoc 对具体参数化泛型本就能正确解析，见 §1 技术澄清） |
| `LogUtil` | 分组翻译失败、导出失败等操作日志 |
| （P2）跨模块错误码体系 | 若 facility/toolbox 未来引入统一数字错误码，`ErrorShapeOperationCustomizer` 可升级为按 endpoint 精确枚举 |

**禁止清单遵循**：不自建 HTTP 客户端、JSON 序列化——spec 格式转换（JSON/YAML/Postman）直接用 springdoc 已生成的 `OpenAPI` 对象 + Jackson（Boot BOM 已管理），不重新解析。

## 4. 核心抽象设计

### 4.1 领域 API（`core`/`export`/`exposure`，无 Toolbox 前缀）

```java
public interface DocsExporter {
    Result<byte[], DocsError> export(String group, ExportFormat format);
    // group 为 null/空白 = 默认文档（未配置分组时的单一 spec）；未知组名 → Err(ExportFailed)，消息含已配置组名列表引导
}

public interface DocsExposureGate {
    boolean isExposed(Environment env);   // 是否应暴露 api-docs / swagger-ui / 导出端点
}

public enum ExportFormat { JSON, YAML, POSTMAN }
```

两者均 `@ConditionalOnMissingBean`（L4 Seam），应用可整体替换默认实现。

### 4.2 错误类型（`DocsError`，与其余四模块同构：只有 `message()`，无数字 code）

```java
public sealed interface DocsError permits ExportFailed { String message(); }
// record ExportFailed(String message) implements DocsError —— 导出：group 不存在 / 格式转换失败
```

**变体收窄说明（v0.1 曾列 3 型，写实现计划前修正）**：最初还列了 `InvalidGroupConfig` 与 `SchemaResolutionFailed` 两型，但二者都不会经 `Result` 通道流动——分组配置错误按全仓原则 #8 在启动期直接抛 `IllegalStateException`（同 storage 缺 `root-dir` 先例，消息含配置路径引导）；schema 命名修饰失败只记日志并退回默认命名，不产生错误值。把永不流经 API 的变体留在 sealed 集里就是死桩（M4 裁定 A 的教训），故 P1 只保留唯一真实流经 `Result` 的 `ExportFailed`。

### 4.3 分组翻译（`grouping`）

分组从表面看符合 00-overview §3.3"同类能力多实例"的特征（多个具名组），但**不采用 Registry + 逻辑名模式**：Registry 服务的是运行时查找（`registry.get(name)` 返回一个业务代码要调用的对象，如 `ObjectStore`/`MailDispatcher`），而分组是启动期一次性的配置翻译，翻译结果被 springdoc 自身消费，应用代码不会在运行时按名字查找某个分组对象。

**翻译机制（v0.1 曾设想 `BeanDefinitionRegistryPostProcessor` 注册 `GroupedOpenApi` bean，写实现计划前修正）**：springdoc 的多分组支持由 `@ConditionalOnBean(GroupedOpenApi.class)` 守卫，该条件在 Spring 加载配置类阶段求值——早于任何我们以 `@Bean` 声明的 BFPP 能注册分组 bean 的时机，届时注册为时已晚，springdoc 的多分组配置整体不装配。改走 springdoc **原生属性面**：springdoc 自身文档化支持 `springdoc.group-configs[N].{group,packages-to-scan,paths-to-match,paths-to-exclude}` 纯属性驱动的多分组（有配套的 AnyNestedCondition 兜住属性路径），本模块用一个 `EnvironmentPostProcessor`（经 `META-INF/spring.factories` 注册，容器构建前运行，零 bean 排序风险）把 `toolbox.docs.groups.<name>.*` 翻译成前者：

```java
class DocsGroupsEnvironmentPostProcessor implements EnvironmentPostProcessor {
    // toolbox.docs.enabled=false 时直接跳过；
    // Binder 绑定 toolbox.docs.groups.<name>.{packages-to-scan|paths-to-match|paths-to-exclude}；
    // 校验：每组至少一个选择器非空，否则抛 IllegalStateException（fail-fast，消息含配置路径）；
    //       —— 组名重复无需校验：map 键天然唯一；
    // 产出 MapPropertySource（springdoc.group-configs[i].*），addLast——消费方显式写的
    // springdoc.* 原生属性优先（README 声明两种配置面不要混用）。
}
```

### 4.4 契约感知（`schema`，旗舰能力，三件事）

```java
class ResultLeakDetector implements SmartInitializingSingleton {
    // 容器刷新末尾遍历 RequestMappingHandlerMapping 的 HandlerMethod 集合，
    // 对声明返回类型（含 ResponseEntity<X> 时取 X）为 cn.code91.facility.result.Result
    // 的方法记 LogUtil 警告（含 handler 类#方法名），不阻断启动——本模块不该让业务应用起不来，
    // 只负责把这处偶发的契约破坏面亮出来。
}
class BaseResponseModelConverter implements ModelConverter {
    // 识别 BaseResponse<T> / ResponseEntity<BaseResponse<T>>，把默认冗长命名
    // （如 BaseResponseUserDto）修饰为更短的 schema 名；不改变已能被正确解析的字段结构。
}
class PageBaseResponseModelConverter implements ModelConverter {
    // 同上，作用于 PageBaseResponse<T>。
}
class ErrorShapeOperationCustomizer implements OperationCustomizer {
    // 为每个 operation 追加通用错误响应 example（code/message/description）
}
```

**L5 扩展点不自设 SPI（v0.1 曾设想 `spi.DocsSchemaCustomizer`，写实现计划前修正）**：springdoc 自身就会收集应用声明的 `OpenApiCustomizer`/`OperationCustomizer`/`ModelConverter` bean——这正是本模块三个定制器接入 springdoc 的同一机制。再包一层自有 SPI 违反"Wrap, don't replace"（给既有扩展点套第二层皮），删除；README 直接引导用户用 springdoc 原生 customizer bean。`ResultLeakDetector` 的告警行为也不做 SPI 化——P1 只有"告警"一种策略，无自定义必要（P2 `strict` 模式见 §10）。

### 4.5 环境门禁的实现机制（请求期过滤，非配置期覆写）

`DocsExposureGate` 的默认实现：命中 `toolbox.docs.exposure.production-profiles`（默认 `[prod, production]`）且 `expose-in-production=false`（默认值）→ 不暴露。

**技术澄清（写实现计划前修正）**：最初设想让 `ToolboxDocsAutoConfiguration` 在 springdoc 自身自动装配**之前**执行（`@AutoConfiguration(beforeName=...)`），据判定结果覆写 springdoc 原生的 `springdoc.api-docs.enabled`/`springdoc.swagger-ui.enabled` 属性——这站不住脚：Spring 对其它自动装配类的 `@ConditionalOnProperty` 求值发生在 `ConfigurationClassPostProcessor` 的 bean-definition-registry 阶段，早于本模块任何 `@Bean` 声明的处理器能执行的时机，届时覆写已经晚了，起不到作用。改为**请求期过滤**：`DocsExposureFilter`（`OncePerRequestFilter`，经 `FilterRegistrationBean` 注册，`urlPatterns` 覆盖 springdoc 的 `springdoc.api-docs.path`（默认 `/v3/api-docs`）、swagger-ui 路径（默认 `/swagger-ui/**`、`/swagger-ui.html`）与本模块 `DocsExportController` 的路径）——`isExposed()` 为 false 时直接响应 404，不落入 springdoc 的 handler。这个机制只依赖 springdoc **对外公开、稳定的路径配置项**，不触碰其内部装配类，比配置期覆写更简单也更抗版本变化。

## 5. 自动装配设计

```java
@AutoConfiguration
@ConditionalOnClass(name = "org.springdoc.core.models.GroupedOpenApi")           // L1
@ConditionalOnWebApplication
@ConditionalOnProperty(prefix = "toolbox.docs", name = "enabled",
                       havingValue = "true", matchIfMissing = true)              // L2
@EnableConfigurationProperties(ToolboxDocsProperties.class)   // 单一根 Properties record + 嵌套子 record（同 storage 先例）
public class ToolboxDocsAutoConfiguration {
    // 分组翻译不在此类：DocsGroupsEnvironmentPostProcessor 经 META-INF/spring.factories 注册（§4.3），
    // 早于容器构建运行；AutoConfiguration.imports 仍只注册本类这一个装配入口。

    @Bean @ConditionalOnMissingBean
    io.swagger.v3.oas.models.OpenAPI toolboxDocsOpenApi(ToolboxDocsProperties props) { … }  // Info/servers/security-schemes → OpenAPI bean（springdoc 消费）

    @Bean @ConditionalOnMissingBean
    DocsExposureGate defaultDocsExposureGate(ToolboxDocsProperties props) { … }  // L4，取 props.exposure()

    @Bean @ConditionalOnMissingBean
    DocsExporter defaultDocsExporter(...) { … }                                   // L4

    @Bean
    BaseResponseModelConverter baseResponseModelConverter() { … }
    @Bean
    PageBaseResponseModelConverter pageBaseResponseModelConverter() { … }
    @Bean
    ErrorShapeOperationCustomizer errorShapeOperationCustomizer() { … }
    @Bean @ConditionalOnMissingBean
    ResultLeakDetector resultLeakDetector(RequestMappingHandlerMapping mapping) { … }   // 契约感知①

    @Bean
    DocsExportController docsExportController(DocsExporter exporter) { … }        // 门禁统一交给下面的过滤器

    @Bean
    FilterRegistrationBean<DocsExposureFilter> toolboxDocsExposureFilter(
            DocsExposureGate gate, ToolboxDocsProperties props) { … }              // §4.5：请求期过滤，覆盖 api-docs/swagger-ui/export 三类路径
}
```

## 6. 配置设计

```yaml
toolbox:
  docs:
    enabled: true
    info:
      title: 示例服务 API
      version: 1.0.0
      description: ...
    security-schemes:
      bearerAuth: { type: http, scheme: bearer, bearer-format: JWT }   # 仅文档展示，不代为鉴权
    servers:
      - { url: https://api.example.com, description: 生产 }
      - { url: http://localhost:8080,   description: 本地 }
    groups:
      admin:
        packages-to-scan: [com.biz.admin]
      open-api:
        paths-to-match: [/api/public/**]
    exposure:
      production-profiles: [prod, production]
      expose-in-production: false     # 显式打开才在生产暴露 api-docs/swagger-ui/export
    export:
      enabled: true                   # 依附于 exposure 判定；生产默认同样不可用
    contract-awareness:
      result-leak-detection: true     # 默认开；极少数场景需要关闭时可显式置 false
```

## 7. 依赖策略

| 依赖 | scope | 说明 |
|---|---|---|
| `cn.code91:server-facility` | compile | `Result`/`BaseResponse`/`PageBaseResponse`/`LogUtil` |
| `spring-boot-autoconfigure`（及本模块字节码直接引用的 spring-boot/spring-context/spring-web/spring-webmvc 等薄层，按依赖账目守卫逐一显式声明） | compile | 装配 + Filter/HandlerMapping/RestController |
| `org.springdoc:springdoc-openapi-starter-common` + `springdoc-openapi-starter-webmvc-api`（2.8.x，落地前复核最新 GA）与其携带的 swagger-core/swagger-models（本模块直接引用 `ModelConverter`/`Schema` 等类型） | **optional** | 本模块字节码直接引用的 springdoc 面；缺失时 L1 不装配 |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 不声明（消费方引入） | swagger-ui 由消费方按需引入（版本仍由父 pom 收口）；本模块字节码不引用 UI 构件 |
| `com.github.xiaoymin:knife4j-openapi3-spring-boot-starter` | 不声明（P2 再议） | UI 皮肤，消费方自行叠加 |

springdoc 缺失时：本模块整体不装配（`@ConditionalOnClass` 缺 springdoc 类型），不影响应用启动——符合"L0 jar 在 classpath 才有意义"的既定模型。

## 8. 使用视角（消费方）

**零代码路径**（最常见）：引入依赖 + 引入 springdoc-openapi-starter-webmvc-ui，写 `toolbox.docs.groups.*`，业务 controller 该怎么写还怎么写——`BaseResponse<T>` 自动展开无需任何注解。

**导出**（编程式，少见）：

```java
Result<byte[], DocsError> spec = docsExporter.export("admin", ExportFormat.POSTMAN);
switch (spec) {
    case Ok(var bytes)             -> respond(bytes);
    case Err(ExportFailed e)       -> notFound(e.message());   // P1 唯一变体（§4.2 收窄说明）
}
```

## 9. 测试策略

| 层 | 手段 | 覆盖点 |
|---|---|---|
| 装配测试 | `ApplicationContextRunner` 矩阵 | springdoc 在/不在 classpath（`FilteredClassLoader`）；`enabled=false`；用户覆盖 `DocsExposureGate`/`DocsExporter`/`OpenAPI` bean 让位 |
| 分组翻译测试 | `MockEnvironment` 直测 `DocsGroupsEnvironmentPostProcessor` | 正常翻译产出 `springdoc.group-configs[i].*`；选择器全空组 fail-fast 且消息含配置路径；`enabled=false` 跳过；无 groups 配置零产出 |
| 门禁测试 | 单测 gate 逻辑 + 集成断言 | prod-profile × expose-in-production 四种组合；命中生产且未显式打开时 `/v3/api-docs`、`/swagger-ui/**`、导出端点均 404 |
| 单元测试 | 直接构造 `Schema`/`Operation` 模型对象 | `BaseResponseModelConverter`/`PageBaseResponseModelConverter` 命名修饰逻辑、`ErrorShapeOperationCustomizer` 错误示例追加逻辑 |
| 集成测试 | 最小 `@SpringBootTest` + 仅测试用 `@RestController`（返回 `BaseResponse<TestDto>`） | 断言 `/v3/api-docs` 真实 JSON 里 `data: TestDto` 已正确解析、schema 命名已修饰（本仓库首次启动内嵌 Web 容器的测试） |
| 泄漏检测测试 | 分别注册返回 `BaseResponse<TestDto>`（正常）与直接返回 `Result<TestDto,TestError>`（误用）的测试用 `@RestController` | `ResultLeakDetector` 仅对后者告警一次，含 handler 类名与方法名；告警不阻断容器启动 |
| 导出测试 | 三种格式转换正确性 + group 不存在 | `DocsExporter` |
| 架构测试 | ArchUnit 三条固定规则 | 包无环；`autoconfigure` 不被主包依赖；`core` 门面不依赖 springdoc/swagger/knife4j 类型（缺 springdoc 时门面仍可加载） |

## 10. 演进路线

| 阶段 | 内容 |
|---|---|
| P1 | springdoc 自动装配 + 配置驱动分组（属性翻译层）+ Info/安全方案/服务器列表配置 + 契约感知三件事（`Result` 泄漏检测告警、`BaseResponse`/`PageBaseResponse` schema 命名修饰、通用错误形状文档）+ 环境门禁（请求期过滤）+ JSON/YAML/Postman 导出 |
| P2 | `DocsAccessGuard` SPI（Basic Auth / IP 白名单，保护文档与导出端点的"谁能看"）；泄漏检测 `strict` 模式（告警升级为启动失败，配置项预留在 `contract-awareness` 下）；knife4j UI 深度集成；按 endpoint 声明具体业务错误变体的注解式枚举（依赖跨模块错误码体系先成熟） |
| P3 | 版本快照与 diff（**不复用** compare-enhancement——模块间零依赖约束，需自研轻量 JSON 结构差异）；桶/域级更细粒度的分组能力对齐（如随 database 的多数据源演进） |
| P4（观望） | WebFlux 支持；多服务文档聚合门户（独立仓库形态，非本模块范围） |

**风险**：
1. springdoc 版本随 Spring Boot 迭代较快（类似 llm 模块 R3 的生态漂移风险）——薄封装 + `@ConditionalOnClass` 字符串探测降低耦合，落地前复核与 Spring Boot 3.5.10 的兼容矩阵；
2. `DocsExposureFilter` 依赖 springdoc 路径配置项（`springdoc.api-docs.path`/swagger-ui 路径）的默认值假设——若消费方改了这些路径但未同步告知本模块的 `urlPatterns` 计算逻辑，会出现"改了路径后门禁失效"；实现需直接读 springdoc 的同名属性而非硬编码默认值，并有集成测试覆盖"生产环境仍能访问 api-docs"这一失败模式；
3. P1 错误响应文档只是通用形状，业务方可能误以为"文档已经很完整"——USAGE.md 需明确标注这一限制，避免误用。
4. `ResultLeakDetector` 只做启动期告警，不阻断（避免把本模块变成"能不能启动"的强控制点）；若团队希望硬性阻断，P2 可加 `strict` 模式（配置项预留在 `contract-awareness` 下），P1 不做。
