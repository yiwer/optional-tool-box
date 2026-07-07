# optional-tool-box 总体设计

> 状态：设计稿 v0.1（尚未编码）｜日期：2026-07-06
> 调研基线：server-facility `0.1.0-SNAPSHOT`（Spring Boot 3.5.10 / Java 21）、beacon-support（beacon-facility / beacon-database / beacon-storage 三个已实现模块）

---

## 1. 背景与定位

**server-facility** 是"每个服务都必须带"的基座库：单模块 jar，提供 `Result<T,E>` 显式错误通道、错误码体系、日志/脱敏/JSON/i18n 门面、Web 基建（traceId、访问日志、全局异常、统一响应）、缓存/锁/限流/幂等等横切能力。

**optional-tool-box** 是"按需引入"的可选能力层：五个相互独立的增强模块，业务项目引入哪个，哪个的能力就自动装配生效；不引入则完全不存在。

```
业务应用
 ├─(必选)── server-facility          ← 基座：Result、错误体系、Web 基建、工具门面
 └─(可选)── optional-tool-box/*      ← 本仓库：按需引入，自动装配
              ├─ compare-enhancement    对象/集合差异对比（审计、对账）
              ├─ database-enhancement   数据库增强（SQL 观测、轻量 CRUD、审计填充）
              ├─ llm-enhancement        大模型接入（多模型注册、结构化输出、用量观测）
              ├─ mail-enhancement       邮件发送（多账号、模板、沙箱防误发）
              └─ storage-enhancement    对象存储统一抽象（OSS/S3/本地、直传、上传守卫）
```

### 1.1 与 server-facility 的关系（硬性决策）

- 每个增强模块 **compile 依赖 server-facility**：统一复用 `Result<T,E>`、`ErrorTypeInterface`、`LogUtil`、`JsonUtil` 等，不重复建设。
- 依赖方式区分"**库 API 依赖**"与"**Bean 依赖**"（beacon ADR-0024 的教训）：优先只使用 facility 的静态门面 / 值类型；facility 的自动装配在消费方应用中本来就会生效，模块自身不注入 facility 的 bean，保证模块可独立测试。
- toolbox 模块之间 **零依赖**，只共享 facility 这一个公共下游。

### 1.2 五个模块一览

| 模块 | artifactId | 一句话定位 | 典型场景 | 参考来源 |
|---|---|---|---|---|
| compare | `compare-enhancement` | 注解驱动的对象/集合差异引擎，零第三方依赖 | 操作审计"改了什么"、数据对账 | 自研（业界 JaVers / java-object-diff 调研见 01 文档） |
| database | `database-enhancement` | ORM 无关的数据库增强：SQL 观测、轻量 CRUD、审计填充、字段加密 | 任意用 spring-jdbc / ORM 的服务 | beacon-database（已实现，最完整参考） |
| llm | `llm-enhancement` | 多模型注册表 + OpenAI 兼容协议薄适配，Result 化错误 | 摘要、抽取、审核、客服问答 | beacon 仅管理 spring-ai-bom，无实现；主要为业界调研 |
| mail | `mail-enhancement` | 多账号邮件派发 + 模板渲染 + 非生产沙箱 | 通知、告警、验证码、报表投递 | beacon-message 仅空壳；基于 spring-boot-starter-mail 设计 |
| storage | `storage-enhancement` | 对象存储统一 Seam：OSS/S3/本地三 adapter + 上传守卫 + 前端直传 | 附件、图片、导出文件 | beacon-storage（已实现，可直接移植架构） |

---

## 2. 设计原则

1. **Deep module**（Ousterhout）：接口面窄（每模块 1~2 个 Seam 接口 + 1 个 AutoConfiguration），承载面宽（adapter、handler、注册表在内部）。这是 facility 与 beacon 两个参考项目共同的立身之本。
2. **Wrap, don't replace**：不重复包装 Spring 既有能力。邮件底层用 `JavaMailSender`，数据库贴着 `NamedParameterJdbcTemplate`，缓存/锁/限流直接用 facility 的——模块只补空白。
3. **显式错误通道**：对外 API 一律返回 `Result<T, XxxError>`，`XxxError` 为 sealed interface，调用方可 pattern matching 穷尽处理。**唯一例外**：database 模块的数据访问路径保留 Spring 异常语义（事务回滚依赖异常传播，Result 化会破坏 `@Transactional`，见 02 文档 §4.5）。
4. **即插即用**：引依赖即装配、删依赖即消失；不需要任何 `@Enable*` / `@Import`。
5. **默认可用 + 处处可换**：默认实现覆盖 ~90% 场景；每个核心 bean 都挂 `@ConditionalOnMissingBean`，应用声明同类型 bean 即替换（Seam 机制）。
6. **重依赖 optional 化**：云 SDK、驱动、模板引擎全部 `<optional>true</optional>`，消费方按所选 provider 自行引入；缺类时相关 bean 直接不装配（`@ConditionalOnClass`）。
7. **供应商差异坦诚化**：provider 能力不齐时（如阿里云 OSS 不支持预签名分片，beacon ADR-0035 实测踩坑）返回一等公民错误 `NotSupported`，不硬模拟、不静默降级。
8. **配置期 fail-fast**：凭证/连接类配置错误在启动期抛异常（如 beacon-storage 在 factory 构造期校验凭证），不留到运行期。

---

## 3. 即插即用机制（核心）

### 3.1 注册方式：AutoConfiguration.imports

每模块固定携带一个注册文件（Spring Boot 3 新式，不用 spring.factories）：

```
{module}/src/main/resources/META-INF/spring/
  org.springframework.boot.autoconfigure.AutoConfiguration.imports
    → cn.code91.toolbox.{module}.autoconfigure.Toolbox{Module}AutoConfiguration
```

每模块 **只有一个** AutoConfiguration 入口（beacon-database / beacon-storage 均如此；facility 因包簇多而拆 11 个，增强模块不需要）。装配顺序用 `@AutoConfiguration(after = ...)` 声明（如 database 在 `DataSourceAutoConfiguration` 之后）。

### 3.2 条件装配分层模型（L0–L5）

所有模块统一按六层组织装配条件，写文档、写代码、排查问题都按这个模型对位：

| 层 | 机制 | 作用 | 示例 |
|---|---|---|---|
| L0 | jar 在 classpath | 模块存在性：引依赖才有 `.imports` 被扫描 | 引入 `mail-enhancement` |
| L1 | `@ConditionalOnClass` / `@ConditionalOnWebApplication` | 环境条件：可选 SDK / Servlet 环境探测 | `@ConditionalOnClass(name="jakarta.mail.internet.MimeMessage")` |
| L2 | `@ConditionalOnProperty(...enabled, matchIfMissing=true)` | 模块总开关：默认开（引依赖已表达意图），可一键关停 | `toolbox.mail.enabled=false` |
| L3 | `type` 选型 + nested `@Configuration` 互斥 | 供应商选择：一个配置项定 adapter，互不实例化 | `toolbox.storage.type: aliyun-oss` |
| L4 | `@ConditionalOnMissingBean` | Bean 级 Seam：应用可覆盖任何默认实现 | 自定义 `ObjectStoreRegistry` |
| L5 | `ObjectProvider<T>` 收集 + `freeze()` | SPI 扩展点：应用 `@Bean` 即自动注册，启动期冻结 | 自定义 `FieldHandler` / `ValueComparator` |

**默认值哲学**：L2 总开关 `matchIfMissing=true`（引依赖=想用）；但**有外部副作用或仅调试用**的子开关默认 `false`（如 `toolbox.database.p6spy.enabled`、`toolbox.mail.sandbox.enabled` 的重定向行为）。

### 3.3 Registry + 逻辑名模式

凡"同类能力多实例"的场景（多存储桶、多邮件账号、多模型），统一用注册表：

```java
ObjectStoreRegistry.get("images")     // 逻辑桶名 → ObjectStore
MailAccountRegistry.get("notice")     // 账号名   → MailDispatcher
LlmClientRegistry.get("default")      // 模型名   → LlmClient
```

- 配置以 map 形式声明实例：`toolbox.storage.aliyun-oss.buckets.images.name: prod-images`。
- **未配置时装配"空 Registry"兜底**（beacon ADR-0018）：模块不报错、应用能启动；`get()` 时抛带引导信息的 `IllegalArgumentException`（告诉你缺了哪段配置）。逻辑名与物理资源解耦，切环境只改配置。

### 3.4 SPI 收集与冻结

扩展点一律定义为接口 + 内置实现 + 启动期收集（beacon-database `PgHandlerRegistry` 模式）：

```java
@Bean
@ConditionalOnMissingBean
XxxRegistry xxxRegistry(ObjectProvider<XxxHandler> handlers,
                        ObjectProvider<XxxRegistryCustomizer> customizers) {
    var registry = new XxxRegistry();
    XxxRegistry.registerBuiltins(registry);
    handlers.orderedStream().forEach(registry::register);      // 应用 @Bean 即注册
    customizers.orderedStream().forEach(c -> c.customize(registry));
    registry.freeze();                                          // 运行期不可变，线程安全
    return registry;
}
```

### 3.5 依赖治理四层（避免把重依赖强加给使用方）

| 层 | 位置 | 做法 |
|---|---|---|
| 1 | 根 pom `dependencyManagement` | 统一 pin 所有第三方版本，子模块不写版本号 |
| 2 | 模块 pom | 云 SDK / 驱动 / 模板引擎标 `<optional>true</optional>` |
| 3 | AutoConfiguration | `@ConditionalOnClass(name="...")` 字符串形式探测，缺类不装配 |
| 4 | 消费方 pom | 按所选 provider 显式引入 SDK（版本仍由第 1 层管理） |

结果：每个模块对消费方的**强制传递依赖只有 server-facility + spring-boot-autoconfigure（+ 该域的 Spring 官方薄层，如 spring-jdbc）**。

---

## 4. 工程规范

### 4.1 坐标与命名

| 项 | 约定 | 示例 |
|---|---|---|
| groupId | `cn.code91` | — |
| artifactId | `{module}-enhancement` | `storage-enhancement`；⚠️ 现 llm 模块 artifactId 为 `llm-enhance`，**需修正为 `llm-enhancement`** |
| 基础包 | `cn.code91.toolbox.{module}` | `cn.code91.toolbox.storage` |
| 配置前缀 | `toolbox.{module}.*`（kebab-case） | `toolbox.storage.type` |
| 装配类 | `Toolbox{Module}AutoConfiguration` | `ToolboxStorageAutoConfiguration` |
| 配置类 | `Toolbox{Module}[Sub]Properties` | `ToolboxMailSandboxProperties` |
| 领域 API | 用领域名，不带前缀 | `ObjectStore` / `MailDispatcher` / `DiffEngine` / `LlmClient` |
| Bean 名 | 有歧义风险时加 `toolbox{Module}` 前缀 | `toolboxStorageAliyunOssRegistry` |
| 错误类型 | `{Module}Error` sealed interface | `StorageError` / `MailError` / `LlmError` / `CompareError` |
| i18n | `i18n/toolbox-{module}-messages*.properties`，注册 MessageSource 参与 facility `AggregatedMessageSource` 聚合 | — |

> 配置前缀选 `toolbox.*` 而非 `facility.*`：与基座配置空间硬隔离，一眼可辨"这是可选件的配置"。beacon 用 `beacon.{module}.*`、facility 用 `facility.*`，本仓库对位仓库名取 `toolbox.*`。

### 4.2 模块标准目录骨架

```
{module}-enhancement/
├── pom.xml
├── README.md                 # 速览：定位、快速开始、配置速查
├── DESIGN.md                 # 设计：分层、Seam、取舍（可后补，初期指向 docs/design/）
└── src/
    ├── main/java/cn/code91/toolbox/{module}/
    │   ├── core/             # Seam 接口、错误类型（sealed）、值对象（record）
    │   ├── spi/              # 扩展点接口 + Customizer
    │   ├── {adapter}/        # 各 provider 实现（aws/aliyun/local、smtp、openai…）
    │   │                     #   每个 adapter 自带 ErrorMapper：SDK 异常 → XxxError
    │   └── autoconfigure/
    │       ├── Toolbox{Module}AutoConfiguration.java
    │       └── Toolbox{Module}*Properties.java
    ├── main/resources/META-INF/spring/…AutoConfiguration.imports
    ├── main/resources/i18n/toolbox-{module}-messages*.properties
    └── test/java/…           # *Test 单测 + *IT 集成测 + ArchitectureTest
```

---

## 5. 父 POM 设计（工程初始化改造清单）

### 5.1 现状问题

1. 无 `spring-boot-dependencies` BOM，子模块无法免版本号引依赖；
2. 无 `dependencyManagement`，第三方版本无收口；
3. 只配了 `maven.compiler.source/target`，缺 `maven.compiler.release`（跨 JDK 编译一致性）；
4. `llm-enhancement/pom.xml` 的 artifactId 为 `llm-enhance`，与目录、兄弟模块不一致；
5. 无公共插件管理（compiler 注解处理器、surefire、jacoco、dependency:analyze）；
6. 模块均无 `src` 目录骨架。

### 5.2 目标父 POM 要点

```xml
<properties>
    <maven.compiler.release>21</maven.compiler.release>
    <spring-boot.version>3.5.10</spring-boot.version>          <!-- 与 server-facility 对齐 -->
    <server-facility.version>0.1.0-SNAPSHOT</server-facility.version>
</properties>

<dependencyManagement>
    <dependencies>
        <dependency>  <!-- ① Spring Boot BOM -->
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-dependencies</artifactId>
            <version>${spring-boot.version}</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
        <dependency>  <!-- ② 基座 -->
            <groupId>cn.code91</groupId>
            <artifactId>server-facility</artifactId>
            <version>${server-facility.version}</version>
        </dependency>
        <!-- ③ 各模块第三方（见 5.3 版本表） -->
    </dependencies>
</dependencyManagement>
```

### 5.3 第三方版本收口表（初值，落地前复核最新 GA）

| 依赖 | 版本 | 用于 | 来源依据 |
|---|---|---|---|
| `com.aliyun.oss:aliyun-sdk-oss` | 3.18.1 | storage | beacon 根 pom 实测值 |
| `software.amazon.awssdk:s3` | 2.41.14 | storage（S3/MinIO/COS/OBS 兼容） | beacon 根 pom 实测值 |
| `p6spy:p6spy` | 3.9.1 | database SQL 观测 | beacon 根 pom 实测值 |
| `com.alibaba:druid-spring-boot-3-starter` | 1.2.23 | database（可选连接池） | beacon 根 pom 实测值 |
| `org.postgresql:postgresql` | 42.7.8 | database PG 方言 | beacon 根 pom 实测值 |
| `org.springframework.ai:spring-ai-bom` | 1.1.4 | llm P3 适配（import 方式） | beacon 根 pom 实测值 |
| `com.icegreen:greenmail-junit5` | 2.1.x | mail 集成测试（test） | 业界标准 |
| `org.wiremock:wiremock` | 3.x | llm 集成测试（test） | 业界标准 |
| `org.testcontainers:*` | 由 Spring Boot BOM 接管 | database/storage 集成测试（test） | 终审修正（2026-07-06）：Boot BOM 自身已 import testcontainers-bom，父 pom 再单独 import 会被先声明的 Boot BOM 架空（死配置），不要重复声明 |
| jakarta-mail / thymeleaf / jackson / lombok | 由 Boot BOM 管理 | mail/公共 | — |

### 5.4 公共构建插件（pluginManagement）

- **compiler**：release 21；annotationProcessorPaths = lombok + spring-boot-configuration-processor（生成配置元数据，IDE 里 `toolbox.*` 有提示）。
- **surefire**：跑 `*Test` 与 `*IT`（集成测试量小，暂不拆 failsafe）。
- **jacoco**：门槛 INSTRUCTION/LINE ≥ 80%、BRANCH ≥ 70%（低于 facility 的 88/88/75，因增强模块含大量 SDK 胶水，adapter 层以集成测试兜底）。
- **maven-dependency-plugin**：`analyze-only` 绑 verify，failOnWarning（学 facility 的依赖账目守卫）。
- **ArchUnit**（test 依赖）：每模块固定三条规则——包无环；`autoconfigure` 不被主包依赖；core/spi 门面不依赖可选 SDK 类型（保证缺 SDK 时门面可加载）。

---

## 6. 复用矩阵（增强模块 ↔ facility）

| 模块 | 必须复用（禁止重造） | 说明 |
|---|---|---|
| compare | `Result`、`DateUtil`（值格式化）、`LocaleUtil`（标签 i18n）、`Hashing`（大对象快速判等，P2） | 差异引擎本体自研，零第三方依赖 |
| database | `LogUtil`、`CryptoUtil`（字段加密 P3）、`IdUtil`（雪花主键策略）、facility web `PageQuery/PageBaseResponse`（分页对接） | 数据访问路径不用 Result（保事务异常语义） |
| llm | `HttpClients`/`RestClient`（同步调用）、`MaskUtil`（提示词/日志脱敏）、`RateLimiterUtil`（客户端限流）、`Async`（异步）、`JsonUtil`（结构化输出解析） | 流式 SSE 用 JDK `java.net.http.HttpClient`，见 03 文档 §4.4 |
| mail | `Result`、`LogUtil`、`Filenames`+`MimeTyping`（附件安全校验）、`RateLimiterUtil`（发送限速）、`LocaleUtil`（多语言邮件） | 底层引擎 = spring-boot-starter-mail 的 `JavaMailSenderImpl` |
| storage | `Result`、`Filenames`（路径穿越/危险扩展名）、`MimeTyping`（MIME 嗅探核验）、`PathIo`（仅 local adapter 目录维护——其 API 只有 deleteDirectory/directorySize，文件读写走 JDK Files；终审修正）、`LogUtil` | 架构直接移植 beacon-storage，另加上传守卫与 local adapter |

**禁止清单**（任何模块不得自建）：HTTP 客户端、JSON 序列化、日志门面、缓存、分布式锁、限流器、脱敏、统一响应模型。

---

## 7. 测试策略总纲

| 层 | 手段 | 覆盖目标 |
|---|---|---|
| 装配测试 | `ApplicationContextRunner` 条件矩阵：有/无 SDK 类、有/无配置、有/无用户覆盖 bean | L1–L4 每条条件路径 |
| 单元测试 | 纯 JVM，H2（database 通用逻辑） | core/spi/renderer/builder |
| 集成测试 | mail→**GreenMail**；storage→**Testcontainers MinIO**（走 aws-s3 adapter + pathStyle）；database→**Testcontainers PostgreSQL**；llm→**WireMock**（模拟 OpenAI 兼容端点，含 SSE） | adapter 真实协议行为 |
| 架构测试 | ArchUnit 三条固定规则（见 §5.4） | 结构不腐化 |

真云（阿里 OSS / AWS / 真实 SMTP / 真实大模型）以手动冒烟清单形式写入各模块 USAGE.md，不进 CI。

---

## 8. 实施路线图

| 里程碑 | 内容 | 理由 |
|---|---|---|
| M0 | 父 pom 改造（§5）+ 五模块 src 骨架 + CI（build/test/jacoco/ArchUnit） | 地基 |
| M1 | **storage** P1 + **compare** P1 | storage 有 beacon 完整参考、风险最低；compare 零依赖、快赢 |
| M2 | **mail** P1 | 依赖 Boot 官方 starter，工作量可控 |
| M3 | **database** P1（观测）→ P2（CRUD 内核） | 体量最大，靠 beacon-database 蓝本压风险 |
| M4 | **llm** P1（同步 + 结构化输出） | 生态漂移最快，放最后以吸收最新版本 |
| M5+ | 各模块 P2+（流式、更多 provider、多数据源…）按业务牵引排期 | 见各模块文档"演进路线" |

---

## 9. 风险与开放问题

| # | 风险/问题 | 影响 | 对策 |
|---|---|---|---|
| R1 | server-facility 仍是 `0.1.0-SNAPSHOT`，API 可能漂移 | 全部模块 | 只依赖其稳定面（Result/错误/静态门面）；推动 facility 尽快打 0.1.0 正式版 |
| R2 | database 方言取向：beacon 是 PG-first，目标业务若主用 MySQL 则 P1 优先级要反转 | database | **开放问题**，需业务侧拍板；设计上已留 `SqlDialect` seam（02 文档 §4.6） |
| R3 | LLM 生态版本漂移快（Spring AI / 各厂协议） | llm | 薄适配 + OpenAI 兼容协议优先；Spring AI 仅作 P3 可选桥接 |
| R4 | facility `HttpClients`（RestClient）不适合 SSE 流式 | llm | 流式路径直接用 JDK HttpClient，零新增依赖（03 文档 §4.4） |
| R5 | 云厂商能力差异（如 OSS 预签名分片不支持，beacon 实测） | storage | `NotSupported` 一等错误 + 各 adapter 能力矩阵表 |
| R6 | 密钥（SMTP 密码、AK/SK、API Key）落 yaml | mail/storage/llm | 强制 `${ENV_VAR}` 占位符约定并写入 USAGE；对接配置中心列为开放问题 |
| R7 | 消费方同时引入 Boot 官方同域自动装配（如 `spring.mail.*`） | mail | 明确共存策略：检测到消费方已有 `JavaMailSender` bean 时将其收编为 primary 账号（04 文档 §5.3） |

---

## 10. 文档索引

- [01 compare-enhancement 设计](01-compare-enhancement.md)
- [02 database-enhancement 设计](02-database-enhancement.md)
- [03 llm-enhancement 设计](03-llm-enhancement.md)
- [04 mail-enhancement 设计](04-mail-enhancement.md)
- [05 storage-enhancement 设计](05-storage-enhancement.md)
