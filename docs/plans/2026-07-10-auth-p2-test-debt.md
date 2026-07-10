# auth-enhancement P2 测试债五项 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 落地 auth-enhancement P1 终审归并的五项测试债（设计 07 §10 P2 行；账本 T2/T3/T4/T5 Minor 备忘）：① 断言补中文 `.as(...)`、② decoder 工厂默认回落分支用例、③ 桥接 filter 异常路径清理钉、④ chainSubSwitch 断言抗重构化、⑤ `@EnableMethodSecurity` 双重声明幂等钉——纯测试改动 + 设计文档 §10 两行"已落地"标注，**零生产码**。

**Architecture:** 全部改动落在 auth-enhancement 既有六个测试类与 `docs/design/07-auth-enhancement.md` §10。新增用例皆为"回归钉"性质——钉住既有生产行为，无 RED 阶段（生产码已实现，写后即绿）；每个新钉内置"用例有效性前提"断言防止空过。`.as()` 补齐为纯打磨，靠全量测试回归保护。

**Tech Stack:** Java 21、JUnit 5 + AssertJ（断言带中文 `.as`）、Spring Boot `WebApplicationContextRunner` 装配矩阵、jakarta.servlet `Filter`/`FilterChain`。**零依赖变更（两个 pom 都不动）**。

## Global Constraints

- **零生产码**：`auth-enhancement/src/main/**` 任何文件不得改动；本迭代唯一非测试改动面是 `docs/design/07-auth-enhancement.md` §10 两行标注（Task 4）。
- 测试风格：AssertJ 断言带中文 `.as(...)`；注释中文，引用 spec 章节号（如 "07 §4.3"）。**`.as()` 补齐只针对本计划列出的 12 处账本钉点，不做全文件扫射式重排**（房内先例：未被账本点名的裸断言"与既有惯例一致"判可接受）。
- 提交信息 `auth-enhancement: 中文摘要`；每任务至少一次提交（小步提交，长任务恢复依赖已落提交）。
- **并行会话纪律**：`git add` 只用步骤明确列出的精确路径，**严禁** `git add -A` / `git add .` / `git commit -a`；提交前先 `git status --short` 核对。
- 工作区：隔离 worktree `D:\Yiwer\code\optional-tool-box\.claude\worktrees\auth-p2-test-debt`（分支 `worktree-auth-p2-test-debt`，基点 master @ 55bf3c2；名义分支名 `feat/auth-p2-test-debt`，harness 生成名等价——P1 先例）。
- Shell 为 Windows PowerShell；多测试类 `-Dtest=A,B`（逗号分隔）；模块内跑测 `mvn -pl auth-enhancement test`（注意：本模块 surefire 会连 KC IT 一起跑且本机 Docker 可用——单任务验证用 `-Dtest=` 过滤避免每次都起容器，最终门禁再全量）。
- 门禁：Task 4 收官 `mvn -pl auth-enhancement clean verify` 全绿（jacoco 80/80/70、dependency:analyze failOnWarning、ArchUnit 三规则、KC IT 真跑——本机 Docker Desktop 已确认可用）。
- 基线：worktree @ 55bf3c2 模块测试 51/51 全绿（含 KC IT 2/2，2026-07-10 实测）。

## 账本出处速查（审查者对照用）

| 项 | 账本条目 | 落点 |
|---|---|---|
| ① `.as()` 补齐 | T2 Minor（两处 hasClientRole）、T3 Minor c（converter/factory 少量）、T4 Minor a（forbiddenShape 4 断言）+ handoff 点名 KeycloakClaimsTest | Task 1，12 处 |
| ② 工厂默认回落 | T3 Minor a（clockSkew/algorithms 默认回落分支无用例） | Task 2 |
| ③ 桥接异常路径 | T4 Minor b（finally 清理无 chain 抛异常路径用例） | Task 2 |
| ④ 断言抗重构 | T5 Minor a（chainSubSwitch 字符串 bean 名断言可改嵌套类断言） | Task 3 |
| ⑤ 幂等钉 | T5 Minor b / 终审 #10（仅静态推理无回归钉）；07 §10"升级 Spring Security 大版本前复核" | Task 3 |

---

### Task 1: 五个测试类断言补中文 `.as()`（12 处，纯打磨）

**Files:**
- Modify: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/core/KeycloakClaimsTest.java`（3 处）
- Modify: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/core/AuthContextTest.java`（2 处）
- Modify: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/jwt/KeycloakJwtAuthenticationConverterTest.java`（1 处）
- Modify: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/jwt/KeycloakJwtDecoderFactoryTest.java`（2 处）
- Modify: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/web/AuthErrorResponseTest.java`（4 处，均在 `forbiddenShape`）

**Interfaces:**
- Consumes: 无。Produces: 无（纯断言描述，不改任何断言语义与期望值）。

- [ ] **Step 1: KeycloakClaimsTest 3 处**

`parsesRealmClientRolesAndScopes` 中：

```java
// 旧
        assertThat(KeycloakClaims.clientRoles(jwt).get("toolbox-api")).containsExactly("doc-reader");
// 新
        assertThat(KeycloakClaims.clientRoles(jwt).get("toolbox-api"))
                .as("client 角色集内容如实读出").containsExactly("doc-reader");
```

`malformedStructuresYieldEmptyCollections` 中：

```java
// 旧
        assertThat(KeycloakClaims.clientRoles(weird)).isEmpty();
        assertThat(KeycloakClaims.scopes(weird)).isEmpty();
// 新
        assertThat(KeycloakClaims.clientRoles(weird))
                .as("resource_access 值畸形（非 Map/roles 非 List）按缺失处理").isEmpty();
        assertThat(KeycloakClaims.scopes(weird)).as("scope 非字符串按缺失处理").isEmpty();
```

- [ ] **Step 2: AuthContextTest 2 处**（账本 T2 点名的"新测试两处 hasClientRole"，位于 `nullValuedClaimAndNullArgumentsAreNeverErrors`）

```java
// 旧
        assertThat(AuthContext.hasClientRole(null, "r")).isFalse();
        assertThat(AuthContext.hasClientRole("c", null)).isFalse();
// 新
        assertThat(AuthContext.hasClientRole(null, "r")).as("null clientId 恒 false 而非 NPE").isFalse();
        assertThat(AuthContext.hasClientRole("c", null)).as("null 角色名恒 false 而非 NPE").isFalse();
```

- [ ] **Step 3: KeycloakJwtAuthenticationConverterTest 1 处**（`realmRolesCanBeDisabled`）

```java
// 旧
        assertThat(authorityNames(converter.convert(kcJwt())))
                .containsExactlyInAnyOrder("SCOPE_openid", "SCOPE_profile");
// 新
        assertThat(authorityNames(converter.convert(kcJwt())))
                .as("map-realm-roles=false：realm 角色不映射，仅剩 SCOPE_（07 §4.2）")
                .containsExactlyInAnyOrder("SCOPE_openid", "SCOPE_profile");
```

- [ ] **Step 4: KeycloakJwtDecoderFactoryTest 2 处**

`issuerOverrideAndAudienceAccepted` 中：

```java
// 旧
        assertThat(decoder).isNotNull();
// 新
        assertThat(decoder).as("issuer 覆写 + audience + 多算法组合同样离线可构建").isNotNull();
```

`propagatesConfigErrors` 第一段（第二段已有 `.as`，不动）：

```java
// 旧
        assertThatThrownBy(() -> KeycloakJwtDecoderFactory.create(
                null, "r", null, null, Duration.ofSeconds(60), List.of("RS256")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toolbox.auth.keycloak.server-url");
// 新
        assertThatThrownBy(() -> KeycloakJwtDecoderFactory.create(
                null, "r", null, null, Duration.ofSeconds(60), List.of("RS256")))
                .as("缺 server-url 经 KeycloakEndpoints 传导 fail-fast（R5）")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toolbox.auth.keycloak.server-url");
```

- [ ] **Step 5: AuthErrorResponseTest 4 处**（均在 `forbiddenShape`；末尾防泄露断言已有 `.as`，不动）

```java
// 旧
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"insufficient_scope\"");
// 新
        assertThat(response.getStatus()).as("403 Forbidden 状态").isEqualTo(403);
        assertThat(response.getHeader("WWW-Authenticate"))
                .as("RFC 6750 头带 insufficient_scope 错误码").isEqualTo("Bearer error=\"insufficient_scope\"");
```

```java
// 旧
        assertThat(body.getCode()).isEqualTo(403);
        assertThat(body.getDescription()).isEqualTo("insufficient_scope");
// 新
        assertThat(body.getCode()).as("BaseResponse.code 与 HTTP 状态一致").isEqualTo(403);
        assertThat(body.getDescription()).as("description 只带错误码（与 401 侧同则）").isEqualTo("insufficient_scope");
```

- [ ] **Step 6: 跑五个测试类确认全绿**

```powershell
mvn -pl auth-enhancement test "-Dtest=KeycloakClaimsTest,AuthContextTest,KeycloakJwtAuthenticationConverterTest,KeycloakJwtDecoderFactoryTest,AuthErrorResponseTest"
```

预期：`Tests run: 20, Failures: 0, Errors: 0`（Claims 3 + AuthContext 5 + Converter 6 + Factory 3 + ErrorResponse 3 = 20，BUILD SUCCESS）。

- [ ] **Step 7: 核对零越界并提交**

```powershell
git status --short
```

预期：仅上述 5 个测试文件为 ` M`。

```powershell
git add auth-enhancement/src/test/java/cn/code91/toolbox/auth/core/KeycloakClaimsTest.java auth-enhancement/src/test/java/cn/code91/toolbox/auth/core/AuthContextTest.java auth-enhancement/src/test/java/cn/code91/toolbox/auth/jwt/KeycloakJwtAuthenticationConverterTest.java auth-enhancement/src/test/java/cn/code91/toolbox/auth/jwt/KeycloakJwtDecoderFactoryTest.java auth-enhancement/src/test/java/cn/code91/toolbox/auth/web/AuthErrorResponseTest.java
git commit -m "auth-enhancement: 测试债——五个测试类断言补中文 .as() 描述（账本 T2/T3/T4 Minor 钉点）"
```

---

### Task 2: 回归钉三枚——decoder 工厂默认回落 ×2 + 桥接 filter 异常路径 ×1

**Files:**
- Modify: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/jwt/KeycloakJwtDecoderFactoryTest.java`（追加 2 个测试方法）
- Modify: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/web/SessionUserBridgeFilterTest.java`（追加 1 个测试方法 + 2 个 import）

**Interfaces:**
- Consumes: `KeycloakJwtDecoderFactory.create(String, String, String, String, Duration, List<String>)`（生产码既有签名，不改）；`SessionUserBridgeFilter.doFilter`（普通 jakarta Filter）。
- Produces: 无。

**性质说明（审查者知悉）**：三枚都是"回归钉"——生产行为已存在（`create` 的 `clockSkew==null→60s`、`resolveAlgorithms(null/空)→RS256` 回落分支；桥接 filter 的 `finally` 清理），无 RED 阶段，写后即绿。防空过手段：桥接钉内置"抛异常前 holder 确已填充"的有效性前提断言；decoder 钉若回落分支缺失会直接 NPE 红。行为级验证（真按 60s/RS256 校验 token）属 WireMock 全链域，本类 Javadoc 已声明"此处只钉能离线构建与 fail-fast 传导"，回落钉遵守该既定范围。

- [ ] **Step 1: KeycloakJwtDecoderFactoryTest 追加默认回落两用例**（放在 `issuerOverrideAndAudienceAccepted` 之后）

```java
    @Test
    void nullClockSkewAndAlgorithmsFallBackToDefaults() {
        // P1 账本 T3 Minor 回归钉：clockSkew=null→60s、jws-algorithms=null→RS256
        // （07 §5.4 默认值）。行为级验证（真按 60s/RS256 校验）属 WireMock 全链域，
        // 此处按本类既定范围钉"回落分支离线可构建"。
        JwtDecoder decoder = KeycloakJwtDecoderFactory.create(
                "https://kc.invalid.example", "r", null, null, null, null);
        assertThat(decoder).as("clockSkew/algorithms 双 null 回落默认值后离线可构建").isNotNull();
    }

    @Test
    void emptyAlgorithmListFallsBackToRs256() {
        JwtDecoder decoder = KeycloakJwtDecoderFactory.create(
                "https://kc.invalid.example", "r", null, null, Duration.ofSeconds(60), List.of());
        assertThat(decoder).as("空算法列表回落 RS256（07 §5.4），非 fail-fast 对象").isNotNull();
    }
```

（无需新 import——`JwtDecoder`/`Duration`/`List`/`assertThat` 均已在文件头。）

- [ ] **Step 2: 跑 decoder 工厂测试确认绿**

```powershell
mvn -pl auth-enhancement test "-Dtest=KeycloakJwtDecoderFactoryTest"
```

预期：`Tests run: 5, Failures: 0, Errors: 0`。

- [ ] **Step 3: SessionUserBridgeFilterTest 追加异常路径钉**

文件头 import 区追加（保持既有分组顺序）：

```java
import jakarta.servlet.FilterChain;
```

（紧邻既有 `import jakarta.servlet.ServletException;`），以及静态导入区追加：

```java
import static org.assertj.core.api.Assertions.assertThatThrownBy;
```

追加测试方法（放在 `populatesDuringChainAndClearsAfter` 之后）：

```java
    @Test
    void clearsHolderEvenWhenChainThrows() {
        // P1 账本 T4 Minor 回归钉：finally 清理在链内异常路径同样成立（07 §4.3
        // "请求后必清"的异常出口半边；与 facility SessionUserClearInterceptor 双清无害）。
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("sub-boom")
                .claim("preferred_username", "carol")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        var auth = new TestingAuthenticationToken(jwt, "n/a");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        AtomicReference<CurrentUser> seenInChain = new AtomicReference<>();
        FilterChain throwingChain = (req, res) -> {
            seenInChain.set(SessionUserHolder.getUser(CurrentUser.class).orElse(null));
            throw new ServletException("链内业务异常");
        };

        assertThatThrownBy(() -> new SessionUserBridgeFilter()
                .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), throwingChain))
                .as("链内异常原样上抛（filter 不吞异常）")
                .isInstanceOf(ServletException.class);

        assertThat(seenInChain.get()).as("抛异常前 holder 确已填充（用例有效性前提，防空过）").isNotNull();
        assertThat(SessionUserHolder.getUser(CurrentUser.class))
                .as("chain 抛异常 finally 仍清理（ThreadLocal 泄漏防护）").isEmpty();
    }
```

注意：新方法无需 `throws` 子句——受检异常都在 `assertThatThrownBy` 的 lambda 内消化（`FilterChain` 单抽象方法声明 `throws IOException, ServletException`，lambda 内直接 `throw new ServletException(...)` 合法）；既有 import `IOException` 仍被其他测试方法使用，不动。

- [ ] **Step 4: 跑桥接测试确认绿**

```powershell
mvn -pl auth-enhancement test "-Dtest=SessionUserBridgeFilterTest"
```

预期：`Tests run: 3, Failures: 0, Errors: 0`。

- [ ] **Step 5: 核对零越界并提交**

```powershell
git status --short
git add auth-enhancement/src/test/java/cn/code91/toolbox/auth/jwt/KeycloakJwtDecoderFactoryTest.java auth-enhancement/src/test/java/cn/code91/toolbox/auth/web/SessionUserBridgeFilterTest.java
git commit -m "auth-enhancement: 测试债——decoder 默认回落分支与桥接 filter 异常路径回归钉"
```

---

### Task 3: 装配矩阵两项——chainSubSwitch 断言抗重构化 + `@EnableMethodSecurity` 双重声明幂等钉

**Files:**
- Modify: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/autoconfigure/ToolboxAuthAutoConfigurationTest.java`

**Interfaces:**
- Consumes: `ToolboxAuthAutoConfiguration.SecurityChainConfiguration` / `ToolboxAuthAutoConfiguration.MethodSecurityConfiguration`（包内可见嵌套配置类；测试与生产类同包 `cn.code91.toolbox.auth.autoconfigure`）。同文件 `methodSecuritySwitchControlsNestedConfig` 已用嵌套类断言模式（先例）。
- Produces: 无。

- [ ] **Step 1: 重写 `chainSubSwitchLeavesOnlyDecoderAndConverter` 断言**

```java
// 旧（完整方法体内的断言与注释段）
                    assertThat(context).hasNotFailed();
                    // 子开关只管本模块的链 bean；测试 runner 为取 HttpSecurity 原型叠加了
                    // SecurityAutoConfiguration，此时无人认领 SecurityFilterChain 插槽，
                    // Boot 官方 defaultSecurityFilterChain 按其自身 @ConditionalOnMissingBean
                    // 语义正常补位——这是真实 Boot 退让语义，不是本模块的产物，故断言本模块专属
                    // bean 名缺席，不断言"全局零 SecurityFilterChain"（07 §5.3：关掉后期望消费方
                    // 自己写链，若消费方也不写，Boot 默认链兜底是标准行为)。
                    assertThat(context).as("Seam 1 子开关：本模块专属链 bean 不存在")
                            .doesNotHaveBean("toolboxAuthSecurityFilterChain");
                    assertThat(context).hasSingleBean(JwtDecoder.class);
                    assertThat(context).hasSingleBean(KeycloakJwtAuthenticationConverter.class);
// 新
                    assertThat(context).hasNotFailed();
                    // 子开关只管本模块的链翼；测试 runner 为取 HttpSecurity 原型叠加了
                    // SecurityAutoConfiguration，此时无人认领 SecurityFilterChain 插槽，
                    // Boot 官方 defaultSecurityFilterChain 按其自身 @ConditionalOnMissingBean
                    // 语义正常补位——这是真实 Boot 退让语义，不是本模块的产物，故不断言
                    // "全局零 SecurityFilterChain"，而以嵌套配置类缺席断言"模块链翼整体不激活"
                    // （类引用抗重构，P1 账本 T5 Minor：字符串 bean 名断言在 bean 方法改名后
                    // doesNotHaveBean(不存在的名) 恒真、静默空过）。
                    assertThat(context).as("Seam 1 子开关：模块链配置整体不激活")
                            .doesNotHaveBean(ToolboxAuthAutoConfiguration.SecurityChainConfiguration.class);
                    assertThat(context).as("decoder/converter 保留（07 §5.3 关链不关件）")
                            .hasSingleBean(JwtDecoder.class);
                    assertThat(context).hasSingleBean(KeycloakJwtAuthenticationConverter.class);
```

- [ ] **Step 2: 追加幂等钉测试与用户配置类**

import 区追加（紧邻既有 `org.springframework.security.*` import）：

```java
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
```

`methodSecuritySwitchControlsNestedConfig` 之后追加测试方法：

```java
    @Test
    void duplicateEnableMethodSecurityDeclarationIsIdempotent() {
        // 07 §10 幂等钉（P1 账本 T5 Minor/终审 #10）：消费方自行 @EnableMethodSecurity 时与
        // 模块 MethodSecurityConfiguration 构成双重声明——@Import 的基础设施配置类按类去重、
        // 同名基础设施 bean 覆盖注册，装配不得冲突失败。该假设依赖 Spring Security 装配内部
        // 行为，升级大版本前以本钉复核（07 §10 触发条件）。
        webRunner.withPropertyValues(MINIMAL)
                .withUserConfiguration(UserMethodSecurityConfig.class)
                .run(context -> {
                    assertThat(context).as("双重 @EnableMethodSecurity 装配不失败（幂等）").hasNotFailed();
                    assertThat(context).as("模块侧 method-security 配置仍在场（开关语义不受用户重复声明影响）")
                            .hasSingleBean(ToolboxAuthAutoConfiguration.MethodSecurityConfiguration.class);
                });
    }
```

内部配置类区（`UserSeamBeansConfig` 之后）追加：

```java
    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class UserMethodSecurityConfig {
    }
```

- [ ] **Step 3: 跑装配矩阵测试确认绿**

```powershell
mvn -pl auth-enhancement test "-Dtest=ToolboxAuthAutoConfigurationTest"
```

预期：`Tests run: 12, Failures: 0, Errors: 0`（原 11 + 新 1）。

- [ ] **Step 4: 核对零越界并提交**

```powershell
git status --short
git add auth-enhancement/src/test/java/cn/code91/toolbox/auth/autoconfigure/ToolboxAuthAutoConfigurationTest.java
git commit -m "auth-enhancement: 测试债——chainSubSwitch 断言抗重构化与 @EnableMethodSecurity 双重声明幂等钉"
```

---

### Task 4: 设计 07 §10 两行"已落地"标注 + 模块 verify 收官

**Files:**
- Modify: `docs/design/07-auth-enhancement.md`（§10 演进表两行，只加标注不删内容）

**Interfaces:** 无。

- [ ] **Step 1: §10 幂等钉行标注**

```markdown
<!-- 旧 -->
| P2 | **实施修正（Task 6 实证补件，§5.4/§4.3 呼应）**：`jwks_unavailable` 与 `invalid_token` 拆分为独立观测码（当前统一归一化为 401/`invalid_token`，见 §5.4）；`@EnableMethodSecurity` 双重声明幂等行为（消费方已自行声明时的覆盖注册语义）补专项回归钉 | 需要更细粒度 IdP 故障可观测性时；升级 Spring Security 大版本前复核该幂等假设 |
<!-- 新 -->
| P2 | **实施修正（Task 6 实证补件，§5.4/§4.3 呼应）**：`jwks_unavailable` 与 `invalid_token` 拆分为独立观测码（当前统一归一化为 401/`invalid_token`，见 §5.4）；`@EnableMethodSecurity` 双重声明幂等行为（消费方已自行声明时的覆盖注册语义）补专项回归钉——**幂等回归钉已落地（2026-07-10 P2 测试债迭代，`duplicateEnableMethodSecurityDeclarationIsIdempotent`）；观测码拆分仍留 P2** | 需要更细粒度 IdP 故障可观测性时；升级 Spring Security 大版本前复核该幂等假设（以已落地的幂等钉复核） |
```

- [ ] **Step 2: §10 测试债归并行标注**

```markdown
<!-- 旧 -->
| P2 | 运行期 fail-open 探测（L1 缺引擎类但检测到 toolbox.auth.* 配置时启动 WARN）；测试债归并（断言补 .as()、工厂默认回落分支、桥接异常路径清理钉、chainSubSwitch 断言抗重构化） | 终审归并（2026-07-10） |
<!-- 新 -->
| P2 | 运行期 fail-open 探测（L1 缺引擎类但检测到 toolbox.auth.* 配置时启动 WARN）；测试债归并（断言补 .as()、工厂默认回落分支、桥接异常路径清理钉、chainSubSwitch 断言抗重构化）——**测试债归并已落地（2026-07-10 P2 测试债迭代）；fail-open 探测仍留 P2** | 终审归并（2026-07-10） |
```

- [ ] **Step 3: 模块全量门禁**

```powershell
mvn -pl auth-enhancement clean verify
```

预期：`BUILD SUCCESS`；`Tests run` 合计 55（基线 51 + 新增 4：decoder 2 + 桥接 1 + 幂等 1），KC IT 2/2 真跑非 skip；jacoco/analyze/ArchUnit 全过。

- [ ] **Step 4: 提交**

```powershell
git status --short
git add docs/design/07-auth-enhancement.md
git commit -m "auth-enhancement: 设计 07 §10 标注——幂等钉与测试债归并已落地，观测码拆分与 fail-open 探测仍留 P2"
```
