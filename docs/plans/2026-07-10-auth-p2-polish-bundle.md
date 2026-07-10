# auth-enhancement P2 打磨捆包七件 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 A/B+C 两轮终审归并裁定的七件小加固（账本"auth P2 打磨捆包"条目）：① emptyAlgorithmList 钉注释精确化、② SecurityChainConfiguration 正向对称断言、③ `TOOLBOX_AUTH_` 分支测试钉、④ WARN"单条"用 `singleElement()` 强制、⑤ 探测器测试 `.as()` 补齐、⑥ `FailOpenWarning` record 紧凑构造器防御拷贝、⑦ `@Lazy(false)` 防全局惰化静默废探测器（含回归钉）。

**Architecture:** 生产码只动 `ToolboxAuthFailOpenDetector.java` 两处（⑥⑦，均自证性加固、零行为语义变化——⑦ 在全局惰化这一此前未定义的场景下把行为钉为"仍探测"）；其余全是测试与注释。⑦ 有真 TDD 循环：先写惰化场景钉（手挂 Boot `LazyInitializationBeanFactoryPostProcessor` 复现 `spring.main.lazy-initialization=true`）确认 RED，再加 `@Lazy(false)` 转绿。③ 为覆盖钉（分支已存在，写后即绿；分支被删则红）。

**Tech Stack:** 同模块既有：JUnit 5 + AssertJ（`singleElement()`）、`WebApplicationContextRunner.withInitializer`（注入 `MapPropertySource` 模拟环境变量源 / 挂惰化后处理器）、logback `ListAppender`。**零依赖变更（pom 不动）**：`@Lazy` 属 spring-context、`LazyInitializationBeanFactoryPostProcessor` 属 spring-boot、`MapPropertySource` 属 spring-core，全部已在依赖面。

## Global Constraints

- 出处权威：A 轮终审 Minor #1 与滚存 #4、B+C 轮终审 Minor #1/#2/#3 与滚存 #1/#2（账本 auth P2 两段有逐条原文）。修法照终审给定，不扩面。
- **生产码改动面 = 仅 `ToolboxAuthFailOpenDetector.java` 的 ⑥⑦ 两处**；不得触碰其它 `src/main/**` 文件。
- 探测器既有语义不变量：四路静默/退让（enabled=false、无键、类在场、非 servlet）行为不变；WARN 文案不变。
- 测试风格：AssertJ 断言带中文 `.as(...)`；注释中文引用出处（终审轮次/章节）。
- 提交信息 `auth-enhancement: 中文摘要`；`git add` 只用步骤列出的精确路径，严禁 `-A`/`.`/`-a`；提交前 `git status --short` 核对。
- **工作区（事故防线，B+C Task 3 教训）**：一切动作前先 `git branch --show-current` 确认输出 `worktree-auth-p2-polish`；工作目录必须是 `D:\Yiwer\code\optional-tool-box\.claude\worktrees\auth-p2-polish`。Shell 为 Windows PowerShell。
- 单任务验证用 `-Dtest=` 过滤；Task 2 收官跑 `mvn -pl auth-enhancement clean verify` 全门禁（预期 63 测试；KC IT 2/2 真跑，Docker 可用）。
- 基线：worktree @ 5d78329 模块 61/61 全绿（2026-07-10 B+C 收官实测）。

---

### Task 1: 探测器加固五件（③④⑤⑥⑦）

**Files:**
- Modify: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/autoconfigure/ToolboxAuthFailOpenDetector.java`（⑥⑦）
- Test: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/autoconfigure/ToolboxAuthFailOpenDetectorTest.java`（③④⑤ + ⑦ 的钉）

**Interfaces:**
- 不改任何公共签名（record 紧凑构造器与 `@Lazy(false)` 均为既有 API 的语义加固）。
- Consumes: `LazyInitializationBeanFactoryPostProcessor`（org.springframework.boot，public，Boot 对 `spring.main.lazy-initialization=true` 的实现件——对显式 lazyInit 已设的定义会跳过，这正是 `@Lazy(false)` 的生效机制）。

- [ ] **Step 1: 测试文件全部改动先行（④⑤ 打磨 + ③⑦ 两个新钉 + import）**

import 区三处追加（保持字典序分组）：

```java
// 旧
import org.springframework.boot.autoconfigure.AutoConfigurations;
// 新
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
```

```java
// 旧
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;
// 新
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
```

`warnsWhenConfigPresentButEngineMissing` 断言段替换（④ `singleElement()` 强制"单条" + ⑤ `.as()` 补齐）：

```java
// 旧
                    assertThat(context).hasNotFailed();
                    assertThat(context).as("探测器激活并产出结果 bean（07 §5.5）")
                            .hasSingleBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class);
                    assertThat(context.getBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class).presentKeys())
                            .as("发现的键入账").contains("toolbox.auth.keycloak.server-url");
                    assertThat(appender.list)
                            .as("fail-open WARN 单条，含裸奔警示与行动指引（README 警示段呼应）")
                            .anySatisfy(event -> {
                                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                                assertThat(event.getFormattedMessage())
                                        .contains("fail-open")
                                        .contains("toolbox.auth.keycloak.server-url")
                                        .contains("spring-boot-starter-oauth2-resource-server")
                                        .contains("toolbox.auth.enabled=false");
                            });
// 新
                    assertThat(context).as("缺引擎类 + 配置在场：上下文照常启动（探测器只警不碍）").hasNotFailed();
                    assertThat(context).as("探测器激活并产出结果 bean（07 §5.5）")
                            .hasSingleBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class);
                    assertThat(context.getBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class).presentKeys())
                            .as("发现的键入账").contains("toolbox.auth.keycloak.server-url");
                    assertThat(appender.list)
                            .as("fail-open WARN 恰为单条（singleElement 强制——终审滚存 #2：anySatisfy 只钉至少一条）")
                            .singleElement()
                            .satisfies(event -> {
                                assertThat(event.getLevel()).as("WARN 级别").isEqualTo(Level.WARN);
                                assertThat(event.getFormattedMessage())
                                        .as("含裸奔警示与行动指引（README 警示段呼应）")
                                        .contains("fail-open")
                                        .contains("toolbox.auth.keycloak.server-url")
                                        .contains("spring-boot-starter-oauth2-resource-server")
                                        .contains("toolbox.auth.enabled=false");
                            });
```

`warnsWhenConfigPresentButEngineMissing` 方法之后追加两个新钉（③⑦）：

```java
    @Test
    void detectsEnvVarFormKeys() {
        // ③ 终审加固钉（B+C Minor #1）：SystemEnvironmentPropertySource 呈现的是裸大写下划线名
        // （如 k8s 注入），presentAuthKeys 的 TOOLBOX_AUTH_ 前缀半分支此前无覆盖——若被删本钉变红。
        // runner 属性源只能造点分格式，经 initializer 头插 MapPropertySource 模拟环境变量源。
        missingEngineRunner()
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("fakeSystemEnv",
                                Map.of("TOOLBOX_AUTH_KEYCLOAK_SERVER_URL", "https://kc.example.com"))))
                .run(context -> {
                    assertThat(context).as("env-var 形态同样激活探测").hasNotFailed();
                    assertThat(context.getBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class).presentKeys())
                            .as("裸大写环境变量名入账（TOOLBOX_AUTH_ 前缀分支）")
                            .contains("TOOLBOX_AUTH_KEYCLOAK_SERVER_URL");
                    assertThat(appender.list).as("WARN 照发且恰单条").singleElement()
                            .satisfies(event -> assertThat(event.getFormattedMessage())
                                    .as("键名进 WARN").contains("TOOLBOX_AUTH_KEYCLOAK_SERVER_URL"));
                });
    }

    @Test
    void warnsEvenUnderGlobalLazyInitialization() {
        // ⑦ 终审加固钉（B+C Minor #3）：全局 spring.main.lazy-initialization=true 时无人注入
        // FailOpenWarning，惰化即静默废掉探测器。Boot 该属性经 SpringApplication 挂
        // LazyInitializationBeanFactoryPostProcessor 生效；runner 不走 SpringApplication，
        // 故手挂同一后处理器复现。该后处理器对已显式设 lazyInit 的定义跳过——@Lazy(false)
        // 正是借此免疫。⚠️ 本测试故意不 getBean/hasSingleBean：取 bean 会强制实例化使断言
        // 空过，唯一有效观察面是"refresh 完成即已 WARN"。
        missingEngineRunner()
                .withPropertyValues("toolbox.auth.keycloak.server-url=https://kc.example.com")
                .withInitializer(ctx -> ctx.addBeanFactoryPostProcessor(
                        new LazyInitializationBeanFactoryPostProcessor()))
                .run(context -> {
                    assertThat(context).as("全局惰化下上下文照常启动").hasNotFailed();
                    assertThat(appender.list)
                            .as("@Lazy(false) 使探测器免于惰化：refresh 即 WARN（删除该注解本钉变红）")
                            .singleElement()
                            .satisfies(event -> assertThat(event.getLevel())
                                    .as("WARN 级别").isEqualTo(Level.WARN));
                });
    }
```

- [ ] **Step 2: 跑测确认 RED 恰为一例**

```powershell
mvn -pl auth-enhancement test "-Dtest=ToolboxAuthFailOpenDetectorTest"
```

预期：`Tests run: 7, Failures: 1`——唯一失败是 `warnsEvenUnderGlobalLazyInitialization`（appender 空：`@Lazy(false)` 尚未加，探测器 bean 被惰化未实例化）。`detectsEnvVarFormKeys` 与既有五例含 `singleElement` 升级应全绿。若失败形态不同，STOP 报告。

- [ ] **Step 3: 生产码两处加固（⑥⑦）**

`ToolboxAuthFailOpenDetector.java` import 追加：

```java
// 旧
import org.springframework.context.annotation.Bean;
// 新
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
```

record 紧凑构造器（⑥）：

```java
// 旧
    /** 探测结果（07 §5.5）：{@code presentKeys} 非空时已发出 WARN；装配矩阵借在场性断言探测器激活。 */
    public record FailOpenWarning(List<String> presentKeys) {
    }
// 新
    /** 探测结果（07 §5.5）：{@code presentKeys} 非空时已发出 WARN；装配矩阵借在场性断言探测器激活。 */
    public record FailOpenWarning(List<String> presentKeys) {
        public FailOpenWarning {
            presentKeys = List.copyOf(presentKeys);
        }
    }
```

`@Bean` 方法加 `@Lazy(false)` 与理由注释（⑦）：

```java
// 旧
    @Bean
    public FailOpenWarning toolboxAuthFailOpenWarning(Environment environment) {
// 新
    /**
     * {@code @Lazy(false)}：全局 {@code spring.main.lazy-initialization=true} 下无人注入本 bean，
     * 惰化 = 探测器静默失效；显式 lazyInit 使 Boot 的惰化后处理器跳过本定义（终审加固，
     * 回归钉 {@code warnsEvenUnderGlobalLazyInitialization}）。
     */
    @Bean
    @Lazy(false)
    public FailOpenWarning toolboxAuthFailOpenWarning(Environment environment) {
```

- [ ] **Step 4: 跑测确认 GREEN**

```powershell
mvn -pl auth-enhancement test "-Dtest=ToolboxAuthFailOpenDetectorTest"
```

预期：`Tests run: 7, Failures: 0, Errors: 0`。

- [ ] **Step 5: 核对零越界并提交**

```powershell
git branch --show-current
git status --short
git add auth-enhancement/src/main/java/cn/code91/toolbox/auth/autoconfigure/ToolboxAuthFailOpenDetector.java auth-enhancement/src/test/java/cn/code91/toolbox/auth/autoconfigure/ToolboxAuthFailOpenDetectorTest.java
git commit -m "auth-enhancement: 打磨捆包——探测器加固（TOOLBOX_AUTH_ 钉、WARN 单条钉、@Lazy(false) 防全局惰化、record 防御拷贝、.as 补齐）"
```

---

### Task 2: A 轮遗留两件（①②）+ 模块 verify 收官

**Files:**
- Modify: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/jwt/KeycloakJwtDecoderFactoryTest.java`（① 注释）
- Modify: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/autoconfigure/ToolboxAuthAutoConfigurationTest.java`（② 一条断言）

**Interfaces:** 无（纯注释 + 纯新增断言；`ToolboxAuthAutoConfiguration.SecurityChainConfiguration` 为同包可见嵌套配置类，同文件 `chainSubSwitchLeavesOnlyDecoderAndConverter` 已用同款类引用）。

- [ ] **Step 1: ① emptyAlgorithmList 钉注释精确化**

```java
// 旧
    @Test
    void emptyAlgorithmListFallsBackToRs256() {
        JwtDecoder decoder = KeycloakJwtDecoderFactory.create(
                "https://kc.invalid.example", "r", null, null, Duration.ofSeconds(60), List.of());
        assertThat(decoder).as("空算法列表回落 RS256（07 §5.4），非 fail-fast 对象").isNotNull();
    }
// 新
    @Test
    void emptyAlgorithmListFallsBackToRs256() {
        // 终审注（A 轮 Minor #1）：Spring 的 JwkSetUri builder 对空算法集自身默认 RS256——本钉
        // 钉的是 API 级契约（空列表≠配置错误，与 propagatesConfigErrors 的 bogus 算法 ISE 对照），
        // 而非模块 resolveAlgorithms 的 ||isEmpty 半分支（删除该半分支本钉仍绿；null 半边由
        // nullClockSkewAndAlgorithmsFallBackToDefaults 钉住，删除即 NPE 红）。
        JwtDecoder decoder = KeycloakJwtDecoderFactory.create(
                "https://kc.invalid.example", "r", null, null, Duration.ofSeconds(60), List.of());
        assertThat(decoder).as("空算法列表回落 RS256（07 §5.4），非 fail-fast 对象").isNotNull();
    }
```

- [ ] **Step 2: ② 链配置正向对称断言**（`assemblesWithMinimalConfig` 内）

```java
// 旧
            assertThat(context).hasSingleBean(SecurityFilterChain.class);
            assertThat(context).hasBean("toolboxAuthMessageSource");
// 新
            assertThat(context).hasSingleBean(SecurityFilterChain.class);
            assertThat(context).as("模块链配置激活（与 chainSubSwitch 关侧嵌套类断言对称，A 轮终审滚存 #4）")
                    .hasSingleBean(ToolboxAuthAutoConfiguration.SecurityChainConfiguration.class);
            assertThat(context).hasBean("toolboxAuthMessageSource");
```

- [ ] **Step 3: 跑两个受影响测试类确认绿**

```powershell
mvn -pl auth-enhancement test "-Dtest=KeycloakJwtDecoderFactoryTest,ToolboxAuthAutoConfigurationTest"
```

预期：`Tests run: 17, Failures: 0, Errors: 0`（Factory 5 + 矩阵 12，数量不变——① 纯注释、② 断言追加非新方法）。

- [ ] **Step 4: 模块全量门禁**

```powershell
mvn -pl auth-enhancement clean verify
```

预期：`BUILD SUCCESS`；`Tests run: 63, Failures: 0, Errors: 0, Skipped: 0`（基线 61 + Task 1 新钉 2；KC IT 2/2 真跑）；jacoco 80/80/70、dependency:analyze、ArchUnit 全过。

- [ ] **Step 5: 核对零越界并提交**

```powershell
git branch --show-current
git status --short
git add auth-enhancement/src/test/java/cn/code91/toolbox/auth/jwt/KeycloakJwtDecoderFactoryTest.java auth-enhancement/src/test/java/cn/code91/toolbox/auth/autoconfigure/ToolboxAuthAutoConfigurationTest.java
git commit -m "auth-enhancement: 打磨捆包——emptyAlgorithmList 钉注释精确化与链配置正向对称断言"
```
