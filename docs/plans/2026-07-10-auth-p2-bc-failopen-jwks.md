# auth-enhancement P2 B+C（fail-open 探测器 + jwks_unavailable 观测码）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按已拍板设计（`docs/design/07-auth-enhancement.md` @ cbfec68，裁定 R6/R7/R8）落地两项：**C** jwks_unavailable 观测码（取键失败与坏 token 在 401 `description` 区分 + WARN），**B** 运行期 fail-open 探测器（缺引擎类且 `toolbox.auth.*` 配置在场 ⇒ 启动 WARN）。

**Architecture:** C：`JwksUnavailableException extends BadJwtException`（jwt 包）——归一化异常家族不变故 401 非 500 语义钉保持，`AuthEntryPoint` 沿 cause 链探测该类型细分 description；协议头 `WWW-Authenticate` 恒为标准码。B：独立轻量 `@AutoConfiguration`（imports 第二行），`@ConditionalOnMissingClass` 与主装配 L1 反相，`@Bean` 扫 Environment 前缀键，产出探测结果 record bean 供装配矩阵断言。

**Tech Stack:** Java 21、Spring Boot 3.5.10 自动装配条件件、facility `LogUtil`、JUnit 5 + AssertJ、logback `ListAppender` 日志断言（llm `OpenAiCompatibleClientLoggingTest` 先例：捕获 `LoggerFactory.getLogger(生产类.class)`，`setAdditive(false)` 保持输出干净）。**零依赖变更（两个 pom 都不动）**。

## Global Constraints

- 设计权威：07 §4.3（观测码段）/ §5.4（收敛注）/ §5.5（探测器）/ §2.4 R6-R8。实现不得偏离已拍板条件栈与语义。
- **语义钉不得破坏**：`JwksUnavailableTest` 的 401 非 500（`JwksUnavailableException` 必须是 `BadJwtException` 子类）；`WWW-Authenticate` 头恒为标准码 `invalid_token`（RFC 6750 不扩展，自定义码只进 body description）；防探测原则（异常内因/网络细节不回显进响应体）。
- 探测器条件栈精确为：`@ConditionalOnMissingClass("org.springframework.security.oauth2.jwt.JwtDecoder")` + `@ConditionalOnWebApplication(type = SERVLET)` + `@ConditionalOnProperty(prefix = "toolbox.auth", name = "enabled", havingValue = "true", matchIfMissing = true)`（R7：`enabled=false` 静默）。
- 测试风格：AssertJ 断言带中文 `.as(...)`；注释中文引用 spec 章节（如 "07 §5.5"）；TDD（真 RED：新断言在实现前必须红）。
- 提交信息 `auth-enhancement: 中文摘要`；每任务至少一提交；`git add` 只用步骤列出的精确路径，**严禁** `-A`/`.`/`-a`；提交前 `git status --short` 核对。
- 工作区：隔离 worktree `D:\Yiwer\code\optional-tool-box\.claude\worktrees\auth-p2-bc`（分支 `worktree-auth-p2-bc`，基点 master @ cbfec68）。Shell 为 Windows PowerShell。
- 单任务验证用 `-Dtest=` 过滤（全量模块 test 会起 Docker KC 容器）；Task 3 收官跑 `mvn -pl auth-enhancement clean verify` 全门禁（jacoco 80/80/70、analyze failOnWarning、ArchUnit、KC IT 真跑——本机 Docker 可用）。
- ArchUnit 边界核对：web→jwt 新增单向 import（无环 ✓）；探测器在 autoconfigure 包（规则 2 无碰）；core 白名单不涉。
- 基线：worktree @ cbfec68 模块 55/55 全绿（2026-07-10 A 项收官实测）。

---

### Task 1: C——JwksUnavailableException + 归一化改造 + EntryPoint cause 链探测

**Files:**
- Create: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/jwt/JwksUnavailableException.java`
- Modify: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/jwt/KeycloakJwtDecoderFactory.java`
- Modify: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/web/AuthEntryPoint.java`
- Test: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/web/AuthErrorResponseTest.java`（+1 用例）
- Test: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/integration/JwksUnavailableTest.java`（升级既有用例：description 断言 + WARN 捕获）

**Interfaces:**
- Produces: `public class JwksUnavailableException extends BadJwtException`，构造 `(String message, Throwable cause)`（jwt 包；Task 3 的 README 文案引用其语义，无代码依赖）。
- 不改任何既有公共签名；`AuthEntryPoint.commence` 行为变化仅限 description 取值。

- [ ] **Step 1: 创建 JwksUnavailableException（纯类型，无独立测试——语义由后续 RED/GREEN 钉）**

```java
package cn.code91.toolbox.auth.jwt;

import org.springframework.security.oauth2.jwt.BadJwtException;

/**
 * JWKS 取键失败（KC 不可达/响应异常）的观测型异常（07 §4.3/§5.4，裁定 R8）。
 * 继承 {@link BadJwtException} 使资源服务器异常路由不变——{@code JwtAuthenticationProvider}
 * 仍转 {@code InvalidBearerTokenException} 走 401 出口（非 500 语义钉，JwksUnavailableTest）；
 * {@code AuthEntryPoint} 沿 cause 链探测本类型，把 401 description 细分为
 * {@code jwks_unavailable}（其余路径仍 {@code invalid_token}）。
 */
public class JwksUnavailableException extends BadJwtException {

    public JwksUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

- [ ] **Step 2: 写 EntryPoint 探测的失败测试**

`AuthErrorResponseTest.java` import 区追加：

```java
import cn.code91.toolbox.auth.jwt.JwksUnavailableException;
```

`forbiddenShape` 之前追加用例：

```java
    @Test
    void jwksUnavailableCauseYieldsDistinctDescription() throws Exception {
        // R8 观测码（07 §4.3）：取键失败经 JwksUnavailableException 归一化，EntryPoint 沿
        // cause 链探测 ⇒ description=jwks_unavailable；协议头保持标准 invalid_token 不扩展。
        var response = new MockHttpServletResponse();
        var jwksDown = new JwksUnavailableException("Couldn't retrieve remote JWK set",
                new java.net.ConnectException("connection refused"));
        new AuthEntryPoint().commence(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(
                        new OAuth2Error("invalid_token", "取键内因不外泄", null), jwksDown));

        assertThat(response.getStatus()).as("仍是 401（IdP 故障不升 500）").isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate"))
                .as("协议头恒标准码（RFC 6750 不扩展）").isEqualTo("Bearer error=\"invalid_token\"");
        BaseResponse<?> body = JsonUtil.deserialize(response.getContentAsString(), BaseResponse.class)
                .get();
        assertThat(body.getDescription()).as("description 细分为 jwks_unavailable（R8）")
                .isEqualTo("jwks_unavailable");
        assertThat(response.getContentAsString())
                .as("网络内因不回显（防探测原则，与既有 401/403 钉同则）")
                .doesNotContain("Couldn't retrieve").doesNotContain("connection refused");
    }
```

- [ ] **Step 3: 跑测确认 RED**

```powershell
mvn -pl auth-enhancement test "-Dtest=AuthErrorResponseTest"
```

预期：`Tests run: 4, Failures: 1`——新用例失败于 description 断言（expected `"jwks_unavailable"` but was `"invalid_token"`）。若失败形态不同（如编译错），先解决再继续。

- [ ] **Step 4: 实现 EntryPoint cause 链探测**

`AuthEntryPoint.java`：import 区追加 `import cn.code91.toolbox.auth.jwt.JwksUnavailableException;`；`commence` 方法体改为：

```java
        String errorCode = authException instanceof OAuth2AuthenticationException oauth
                ? oauth.getError().getErrorCode() : null;
        String description = jwksUnavailable(authException) ? "jwks_unavailable" : errorCode;
        LogUtil.debug("auth-enhancement 401：uri={}，error={}", request.getRequestURI(), description);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate",
                errorCode == null ? "Bearer" : "Bearer error=\"" + errorCode + "\"");
        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                "toolbox.auth.unauthorized", "Authentication required or token invalid", description);
```

类内追加私有探测方法（`writeJson` 之前）：

```java
    /** 沿 cause 链探测取键失败标记（07 §4.3，R8）；带深度上限防异常链成环。 */
    private static boolean jwksUnavailable(Throwable failure) {
        Throwable cur = failure;
        for (int depth = 0; cur != null && depth < 20; depth++) {
            if (cur instanceof JwksUnavailableException) {
                return true;
            }
            cur = cur.getCause();
        }
        return false;
    }
```

类 Javadoc 精确替换（两行）：

```java
// 旧
 * {@code WWW-Authenticate} 头不破标准客户端。description 只带 OAuth2 错误码
 * （invalid_token 等），异常内因不回显（防探测）。
// 新
 * {@code WWW-Authenticate} 头不破标准客户端。description 只带观测码（invalid_token 等；
 * 取键失败经 cause 链探测细分为 jwks_unavailable，07 §4.3/R8），异常内因不回显（防探测）。
```

- [ ] **Step 5: 跑测确认 GREEN**

```powershell
mvn -pl auth-enhancement test "-Dtest=AuthErrorResponseTest"
```

预期：`Tests run: 4, Failures: 0, Errors: 0`。

- [ ] **Step 6: 升级 JwksUnavailableTest（先跑确认 RED 再改工厂）**

`JwksUnavailableTest.java` 整文件替换为：

```java
package cn.code91.toolbox.auth.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.code91.toolbox.auth.jwt.KeycloakJwtDecoderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KC 不可达语义钉（07 §5.4）：服务照常启动（构建期零网络），bearer 请求得 401 而非 500——
 * IdP 故障不得放大为服务端错误。9 端口（discard）loopback 连接必被快速拒绝。
 * P2 R8 升级：401 description 细分为 jwks_unavailable（与坏 token 的 invalid_token 区分，
 * 07 §4.3）且取键失败分支打 WARN 单条（运维可见性）。
 */
@SpringBootTest(classes = AuthTestApp.class, properties = {
        "toolbox.auth.keycloak.server-url=http://127.0.0.1:9",
        "toolbox.auth.keycloak.realm=it" })
@AutoConfigureMockMvc
@DisplayName("JWKS 不可达语义")
class JwksUnavailableTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void unreachableJwksYields401Not500() throws Exception {
        // WARN 断言经内存 appender 捕获（llm OpenAiCompatibleClientLoggingTest 先例）；
        // setAdditive(false) 使预期 WARN 不落控制台，测试输出保持干净。
        Logger factoryLogger = (Logger) LoggerFactory.getLogger(KeycloakJwtDecoderFactory.class);
        boolean originalAdditive = factoryLogger.isAdditive();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        factoryLogger.setAdditive(false);
        factoryLogger.addAppender(appender);
        try {
            String token = TestTokens.mint("http://127.0.0.1:9/realms/it", b -> { });
            mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(401))
                    .andExpect(jsonPath("$.description").value("jwks_unavailable"));
            assertThat(appender.list)
                    .as("取键失败打 WARN 单条（R8 运维可见性），消息为网络内因摘要、无 token 成分")
                    .anySatisfy(event -> {
                        assertThat(event.getLevel()).isEqualTo(Level.WARN);
                        assertThat(event.getFormattedMessage()).contains("JWKS 取键失败");
                    });
        } finally {
            factoryLogger.detachAppender(appender);
            factoryLogger.setAdditive(originalAdditive);
        }
    }
}
```

跑测确认 RED：

```powershell
mvn -pl auth-enhancement test "-Dtest=JwksUnavailableTest"
```

预期：`Tests run: 1, Failures: 1`——失败于 `$.description` 断言（现值 `invalid_token`）。

- [ ] **Step 7: 改造 normalizeDecodeFailures**

`KeycloakJwtDecoderFactory.java`：import 区追加 `import cn.code91.facility.log.LogUtil;`；`normalizeDecodeFailures` 的 catch 段改为：

```java
            } catch (BadJwtException alreadyBad) {
                throw alreadyBad;
            } catch (JwtException generic) {
                LogUtil.warn("auth-enhancement JWKS 取键失败（KC 不可达或响应异常），"
                        + "本次请求按 401/jwks_unavailable 处理：{}", generic.getMessage());
                throw new JwksUnavailableException(generic.getMessage(), generic);
            }
```

该方法 Javadoc 末两句（自"IdP 暂时不可达是运行期常态而非服务端 bug"起）改为：

```
     * IdP 暂时不可达是运行期常态而非服务端 bug，不得放大为 500——此处把"非 Bad 的 JwtException"
     * 升格为 {@link JwksUnavailableException}（BadJwtException 子类，复用既有 401 出口，且被
     * {@code AuthEntryPoint} 沿 cause 链探测细分为 {@code jwks_unavailable} 观测码，07 §4.3/R8）
     * 并打 WARN 单条（R8 运维可见性）；非 JwtException 家族的异常（真正的内部错误）不受影响，
     * 仍按原样向上抛。
```

- [ ] **Step 8: 跑测确认 GREEN + 相邻回归**

```powershell
mvn -pl auth-enhancement test "-Dtest=JwksUnavailableTest,JwksFullChainTest,AuthErrorResponseTest,KeycloakJwtDecoderFactoryTest"
```

预期：`Tests run: 19, Failures: 0, Errors: 0`（JwksUnavailable 1 + JwksFullChain 9 + AuthErrorResponse 4 + DecoderFactory 5；JwksFullChainTest 的坏 token 各 401 场景 description 仍 `invalid_token`，不受影响）。

- [ ] **Step 9: 核对零越界并提交**

```powershell
git status --short
git add auth-enhancement/src/main/java/cn/code91/toolbox/auth/jwt/JwksUnavailableException.java auth-enhancement/src/main/java/cn/code91/toolbox/auth/jwt/KeycloakJwtDecoderFactory.java auth-enhancement/src/main/java/cn/code91/toolbox/auth/web/AuthEntryPoint.java auth-enhancement/src/test/java/cn/code91/toolbox/auth/web/AuthErrorResponseTest.java auth-enhancement/src/test/java/cn/code91/toolbox/auth/integration/JwksUnavailableTest.java
git commit -m "auth-enhancement: jwks_unavailable 观测码——JwksUnavailableException 归一化与 EntryPoint cause 链探测（R8）"
```

---

### Task 2: B——ToolboxAuthFailOpenDetector 探测器

**Files:**
- Create: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/autoconfigure/ToolboxAuthFailOpenDetector.java`
- Modify: `auth-enhancement/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（现仅一行 `cn.code91.toolbox.auth.autoconfigure.ToolboxAuthAutoConfiguration`）
- Test: Create `auth-enhancement/src/test/java/cn/code91/toolbox/auth/autoconfigure/ToolboxAuthFailOpenDetectorTest.java`

**Interfaces:**
- Consumes: 无（与 Task 1 完全独立）。
- Produces: `ToolboxAuthFailOpenDetector.FailOpenWarning`（public 嵌套 record，`List<String> presentKeys`）；Task 3 的 README 文案引用探测器语义，无代码依赖。

- [ ] **Step 1: 写探测器失败测试（类不存在 ⇒ 编译失败即 RED）**

创建 `ToolboxAuthFailOpenDetectorTest.java`：

```java
package cn.code91.toolbox.auth.autoconfigure;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fail-open 探测器（07 §5.5，R6/R7）：缺引擎类 + toolbox.auth.* 配置在场 ⇒ 启动 WARN；
 * enabled=false 显式退出静默；无键静默；类在场/非 servlet 整体退让。WARN 经内存 appender
 * 断言（llm logging 测试先例），setAdditive(false) 保持测试输出干净。
 */
@DisplayName("ToolboxAuthFailOpenDetector fail-open 探测")
class ToolboxAuthFailOpenDetectorTest {

    private Logger detectorLogger;
    private boolean originalAdditive;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLog() {
        detectorLogger = (Logger) LoggerFactory.getLogger(ToolboxAuthFailOpenDetector.class);
        originalAdditive = detectorLogger.isAdditive();
        appender = new ListAppender<>();
        appender.start();
        detectorLogger.setAdditive(false);
        detectorLogger.addAppender(appender);
    }

    @AfterEach
    void restoreLog() {
        detectorLogger.detachAppender(appender);
        detectorLogger.setAdditive(originalAdditive);
    }

    /** 缺引擎类的 servlet web runner（探测器主场景）。 */
    private WebApplicationContextRunner missingEngineRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolboxAuthFailOpenDetector.class))
                .withClassLoader(new FilteredClassLoader(JwtDecoder.class));
    }

    @Test
    void warnsWhenConfigPresentButEngineMissing() {
        missingEngineRunner()
                .withPropertyValues("toolbox.auth.keycloak.server-url=https://kc.example.com")
                .run(context -> {
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
                });
    }

    @Test
    void silentWhenExplicitlyDisabled() {
        missingEngineRunner()
                .withPropertyValues("toolbox.auth.enabled=false",
                        "toolbox.auth.keycloak.server-url=https://kc.example.com")
                .run(context -> {
                    assertThat(context).as("R7：enabled=false 显式退出，探测器整体不装配")
                            .doesNotHaveBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class);
                    assertThat(appender.list).as("无 WARN").isEmpty();
                });
    }

    @Test
    void silentWhenNoAuthKeysPresent() {
        missingEngineRunner().run(context -> {
            assertThat(context).as("探测器装配（enabled 缺省 matchIfMissing）")
                    .hasSingleBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class);
            assertThat(context.getBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class).presentKeys())
                    .as("无 toolbox.auth.* 键").isEmpty();
            assertThat(appender.list).as("无使用意图不骚扰（R7）").isEmpty();
        });
    }

    @Test
    void backsOffWhenEngineClassPresent() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolboxAuthFailOpenDetector.class))
                .withPropertyValues("toolbox.auth.keycloak.server-url=https://kc.example.com")
                .run(context -> assertThat(context)
                        .as("引擎类在场（L1 成立）⇒ 探测器反相条件不成立，整体退让")
                        .doesNotHaveBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class));
    }

    @Test
    void backsOffOutsideServletWeb() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolboxAuthFailOpenDetector.class))
                .withClassLoader(new FilteredClassLoader(JwtDecoder.class))
                .withPropertyValues("toolbox.auth.keycloak.server-url=https://kc.example.com")
                .run(context -> assertThat(context)
                        .as("非 servlet web 无裸奔面，不警（07 §5.5）")
                        .doesNotHaveBean(ToolboxAuthFailOpenDetector.FailOpenWarning.class));
    }
}
```

- [ ] **Step 2: 跑测确认 RED（编译失败：ToolboxAuthFailOpenDetector 不存在）**

```powershell
mvn -pl auth-enhancement test "-Dtest=ToolboxAuthFailOpenDetectorTest"
```

预期：COMPILATION ERROR（symbol not found）。

- [ ] **Step 3: 实现探测器**

创建 `ToolboxAuthFailOpenDetector.java`：

```java
package cn.code91.toolbox.auth.autoconfigure;

import cn.code91.facility.log.LogUtil;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 运行期 fail-open 探测器（07 §5.5，裁定 R6/R7）：L1 缺引擎类的退出态是 fail-open——依赖
 * 重构误 exclude starter 时应用静默裸奔（README 警示段）。本装配与主装配的
 * {@code @ConditionalOnClass} 恰成反相：引擎类缺失时它恰好活着，检测到 {@code toolbox.auth.*}
 * 配置在场即打 WARN 单条（含行动指引）；{@code enabled=false} 显式退出静默（R7，复用 L2
 * 条件语义）；非 servlet web 应用无裸奔面，不警。独立注册于 AutoConfiguration.imports
 * 第二行（R6：否决 EnvironmentPostProcessor / ApplicationListener，见 §2.4）。
 */
@AutoConfiguration
@ConditionalOnMissingClass("org.springframework.security.oauth2.jwt.JwtDecoder")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "toolbox.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ToolboxAuthFailOpenDetector {

    /** 探测结果（07 §5.5）：{@code presentKeys} 非空时已发出 WARN；装配矩阵借在场性断言探测器激活。 */
    public record FailOpenWarning(List<String> presentKeys) {
    }

    @Bean
    public FailOpenWarning toolboxAuthFailOpenWarning(Environment environment) {
        List<String> keys = presentAuthKeys(environment);
        if (!keys.isEmpty()) {
            LogUtil.warn("auth-enhancement 检测到 toolbox.auth.* 配置在场（{}）但鉴权引擎类缺失"
                    + "（spring-security-oauth2-jose 不在 classpath）——模块零装配，应用当前无任何"
                    + "鉴权保护（fail-open）。请引入 spring-boot-starter-oauth2-resource-server；"
                    + "确认弃用则设 toolbox.auth.enabled=false（07 §5.5）。", keys);
        }
        return new FailOpenWarning(keys);
    }

    /**
     * 扫全部可枚举 PropertySource 收集 {@code toolbox.auth.} 前缀键；环境变量形态经 Boot 松绑
     * 呈现为 {@code TOOLBOX_AUTH_} 前缀（如 k8s 注入），一并识别。TreeSet 排序去重，WARN 输出稳定。
     */
    private static List<String> presentAuthKeys(Environment environment) {
        if (!(environment instanceof ConfigurableEnvironment configurable)) {
            return List.of();
        }
        Set<String> keys = new TreeSet<>();
        for (PropertySource<?> source : configurable.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (String name : enumerable.getPropertyNames()) {
                    if (name.startsWith("toolbox.auth.") || name.startsWith("TOOLBOX_AUTH_")) {
                        keys.add(name);
                    }
                }
            }
        }
        return List.copyOf(keys);
    }
}
```

`AutoConfiguration.imports` 文件改为两行：

```
cn.code91.toolbox.auth.autoconfigure.ToolboxAuthAutoConfiguration
cn.code91.toolbox.auth.autoconfigure.ToolboxAuthFailOpenDetector
```

- [ ] **Step 4: 跑测确认 GREEN + 主装配矩阵回归**

```powershell
mvn -pl auth-enhancement test "-Dtest=ToolboxAuthFailOpenDetectorTest,ToolboxAuthAutoConfigurationTest"
```

预期：`Tests run: 17, Failures: 0`（探测器 5 + 主矩阵 12；主矩阵 runner 只注册主装配类，与探测器互不干扰）。

- [ ] **Step 5: 核对零越界并提交**

```powershell
git status --short
git add auth-enhancement/src/main/java/cn/code91/toolbox/auth/autoconfigure/ToolboxAuthFailOpenDetector.java auth-enhancement/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports auth-enhancement/src/test/java/cn/code91/toolbox/auth/autoconfigure/ToolboxAuthFailOpenDetectorTest.java
git commit -m "auth-enhancement: fail-open 探测器——缺引擎类且配置在场启动 WARN（R6/R7）"
```

---

### Task 3: README 披露 + 设计 §10 落地标注 + 模块 verify 收官

**Files:**
- Modify: `auth-enhancement/README.md`（两处增补）
- Modify: `docs/design/07-auth-enhancement.md`（§10 两行"实施中"→"已落地"）

**Interfaces:** 无。

- [ ] **Step 1: README fail-open 警示段增补**

```markdown
<!-- 旧 -->
⚠️ 注意：缺引擎类的退出态是**无任何鉴权保护（fail-open）**，不是降级——依赖重构时若 starter 被意外 exclude，应用会静默裸奔。`enabled=false` / `security-chain.enabled=false` 两种退出态则落到 Boot 默认链（fail-closed），性质不同。
<!-- 新 -->
⚠️ 注意：缺引擎类的退出态是**无任何鉴权保护（fail-open）**，不是降级——依赖重构时若 starter 被意外 exclude，应用会静默裸奔。`enabled=false` / `security-chain.enabled=false` 两种退出态则落到 Boot 默认链（fail-closed），性质不同。缓解：缺引擎类但检测到 `toolbox.auth.*` 配置在场时，启动期打一条 WARN（fail-open 探测器，设计 §5.5；`enabled=false` 显式退出不警）——WARN 是提醒不是保护，依赖治理仍是第一道防线。
```

- [ ] **Step 2: README 缺配置行为段增补观测码说明**

```markdown
<!-- 旧 -->
引 jar +`enabled`（默认开）+ `server-url`/`realm` 缺失或畸形 ⇒ **启动失败**，异常消息携带最小可用
配置样例（fail-fast，对位 00-overview 设计原则 8）。issuer/JWKS 端点本地派生，构建期不发任何网络
请求——KC 暂时不可达不影响启动，bearer 请求得 401（不会放大为 500）。"引了暂不用"的合法出口是
`toolbox.auth.enabled=false`。
<!-- 新 -->
引 jar +`enabled`（默认开）+ `server-url`/`realm` 缺失或畸形 ⇒ **启动失败**，异常消息携带最小可用
配置样例（fail-fast，对位 00-overview 设计原则 8）。issuer/JWKS 端点本地派生，构建期不发任何网络
请求——KC 暂时不可达不影响启动，bearer 请求得 401（不会放大为 500）。KC 不可达的 401 与坏 token
的 401 在响应体 `description` 上区分：前者 `jwks_unavailable`（同时打 WARN 一条），后者
`invalid_token`（设计 §4.3）；`WWW-Authenticate` 头恒为标准 `invalid_token` 不扩展。"引了暂不用"
的合法出口是 `toolbox.auth.enabled=false`。
```

- [ ] **Step 3: 设计 §10 两行落地标注**

```markdown
<!-- 旧（行内片段） -->
观测码拆分已裁定（R8，2026-07-10，§4.3/§5.4）实施中
<!-- 新 -->
观测码拆分已落地（R8，2026-07-10 B+C 迭代，§4.3/§5.4）
```

```markdown
<!-- 旧（行内片段） -->
fail-open 探测已裁定（R6/R7，2026-07-10，§5.5）实施中
<!-- 新 -->
fail-open 探测已落地（R6/R7，2026-07-10 B+C 迭代，§5.5）
```

- [ ] **Step 4: 模块全量门禁**

```powershell
mvn -pl auth-enhancement clean verify
```

预期：`BUILD SUCCESS`；测试总数 61（基线 55 + AuthErrorResponseTest +1 + 探测器 +5），0 失败 0 跳过（KC IT 2/2 真跑）；jacoco 80/80/70、dependency:analyze、ArchUnit 全过（web→jwt 新 import 无环；探测器在 autoconfigure 包）。

- [ ] **Step 5: 核对零越界并提交**

```powershell
git status --short
git add auth-enhancement/README.md docs/design/07-auth-enhancement.md
git commit -m "auth-enhancement: README 观测码与探测器披露 + 设计 §10 两行落地标注"
```
