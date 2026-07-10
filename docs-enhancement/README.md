# docs-enhancement

springdoc-openapi 薄封装：引依赖即获得 OpenAPI 3 接口文档能力——配置驱动分组、Info/服务器/安全方案
声明、**facility 契约感知**（`Result` 泄漏检测告警、`BaseResponse`/`PageBaseResponse` schema 命名修饰、
统一错误形状文档）、生产环境暴露门禁、JSON/YAML/Postman 导出。设计全文见
[docs/design/06-docs-enhancement.md](../docs/design/06-docs-enhancement.md)。

## 快速开始

```xml
<dependency>
    <groupId>cn.code91</groupId>
    <artifactId>docs-enhancement</artifactId>
</dependency>
<!-- 本模块的 springdoc 依赖全部 optional，按需自行引入（版本由父 pom 收口，无需写版本号）。
     只要生成 spec（如仅供导出/网关消费）：引 webmvc-api；要 swagger-ui 页面：引 webmvc-ui（已含前者）。 -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
</dependency>
```

零代码即用：业务 controller 照常返回 `BaseResponse<T>`/`PageBaseResponse<T>`，无需任何注解；
`/v3/api-docs` 即见 schema 名已修饰为 `XxxResponse`/`XxxPageResponse`，且每个 operation 带统一的
`default` 错误响应（`ToolboxErrorResponse`）。不引 springdoc 则本模块整体不装配，应用不受影响。

## 配置速查（`toolbox.docs.*`）

```yaml
toolbox:
  docs:
    enabled: true                       # 总开关，默认 true
    info:
      title: 示例服务 API
      version: 1.0.0
      description: ...
    servers:
      - { url: https://api.example.com, description: 生产 }
    security-schemes:                   # 仅文档展示，不代为鉴权
      bearerAuth: { type: http, scheme: bearer, bearer-format: JWT }
    groups:                             # 具名分组；每组至少配 packages-to-scan 或 paths-to-match 之一
      admin:
        packages-to-scan: [com.biz.admin]
      open-api:
        paths-to-match: [/api/public/**]
    exposure:
      production-profiles: [prod, production]   # 命中即视为生产
      expose-in-production: false               # 默认生产不暴露文档/导出，显式打开才恢复
    export:
      enabled: true                     # GET /toolbox/docs/export?group=&format=（json/yaml/postman）
    contract-awareness:
      result-leak-detection: true       # 控制器直接返回 Result<T,E> 时启动期告警
```

## 分组：两种配置面不要混用

`toolbox.docs.groups.*` 是 **springdoc 原生 `springdoc.group-configs[]` 的配置面翻译层**
（EnvironmentPostProcessor 在容器构建前翻译），不是重新实现的分组机制。请二选一：
要么全部写 `toolbox.docs.groups.*`，要么全部写 springdoc 原生属性。混用时原生属性优先
（翻译产物挂在最低优先级），同名索引会逐键遮蔽、极易困惑。
定制扩展也走 springdoc 原生机制：声明 `OpenApiCustomizer`/`OperationCustomizer`/`ModelConverter`
bean 即被 springdoc 收集，本模块不另设 SPI。

## 可替换 Seam（`@ConditionalOnMissingBean`）

应用声明同类型 bean 即覆盖默认实现：`io.swagger.v3.oas.models.OpenAPI`（文档基底）、
`DocsExposureGate`（暴露门禁判定）、`DocsExporter`（导出）、`ResultLeakDetector`（泄漏检测）。

## P1 限制（坦诚披露）

- **错误响应文档只是通用形状**：facility 各错误类型暂无数字 code，`ToolboxErrorResponse` 仅描述
  `BaseResponse` 错误分支的形状（code/message/description/data），**不能**按 endpoint 枚举具体业务
  错误；不要误以为错误文档已经完整（按 endpoint 精确枚举待跨模块错误码体系成熟，P2）。
- **泄漏检测只告警不阻断**：控制器直接返回 `Result<T,E>` 时启动日志出 WARN（含 `类名#方法名` 与
  修正引导），应用照常启动；需要硬阻断的 strict 模式属 P2。
- **Postman 导出为最小可用**：collection v2.1 仅含逐 endpoint 的 method/url/name（summary →
  operationId → `METHOD path` 三级兜底），不含 auth/body/示例。
- **Servlet-only**：对齐 facility 现有 Web 基建技术栈，WebFlux 不在 P1 范围。
- **门禁覆盖路径**：`springdoc.api-docs.path`（含 `/{group}` 子路径与 `.yaml` 变体端点，即默认的
  `/v3/api-docs.yaml` 与 `/v3/api-docs.yaml/{group}`）、`springdoc.swagger-ui.path`、`/swagger-ui/*`、
  `/toolbox/docs/export`——api-docs/swagger-ui 路径读 springdoc 同名属性，消费方改路径时门禁自动跟随。
- **注册文件的唯一例外**：模块装配入口只有 `ToolboxDocsAutoConfiguration`
  （`META-INF/spring/...AutoConfiguration.imports`）；另有 `META-INF/spring.factories` 注册
  `DocsGroupsEnvironmentPostProcessor`——Boot 3 对 EnvironmentPostProcessor 仍只认 spring.factories，
  机制必需而非破例（文件头注释同此说明）。
