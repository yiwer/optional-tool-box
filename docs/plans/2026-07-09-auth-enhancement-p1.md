# auth-enhancement P1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按已获批 spec（`docs/design/07-auth-enhancement.md` @ 85b5f4b，裁定 R1-R5）落地 auth-enhancement P1——Keycloak 资源服务器鉴权模块：JWT 本地校验 + realm/client 角色映射 + 开箱安全链 + facility `BaseResponse` 401/403 对齐。

**Architecture:** 零 Keycloak 运行时依赖（纯 Spring Security 标准件）。四包结构 core/jwt/web/autoconfigure；`toolbox.auth.*` 自有配置域，模块自建 `JwtDecoder` 令 Boot 官方装配退让（R4）；缺必填配置启动期失败（R5，对 ADR-0018 有意偏离）；JWKS 懒加载（启动零网络请求）。

**Tech Stack:** Java 21、Spring Boot 3.5.10（Spring Security 6.5.x，全由 Boot BOM 管理）、WireMock 3.13.2（假 JWKS 主矩阵）、dasniko testcontainers-keycloak 4.1.1（真 KC IT，父 pom 新增唯一 test pin）、JUnit 5 + AssertJ + ArchUnit。

## Global Constraints

- 不用 Lombok；对外无 Result 返回面（P1 无可失败 API 操作，豁免披露见 spec §3/§4.4——**不要**"顺手"加 `AuthError`）。
- 运行时依赖零新增版本 pin（spring-security 全系 Boot BOM 管）；父 pom 加 `<module>` + 两条 test 件 pin：`testcontainers-keycloak` 与 `nimbus-jose-jwt`（**Task 1 实施实测修正**：Boot BOM 与 spring-security-bom 均不管理 com.nimbusds 坐标，测试直接使用须显式声明→版本收口父 pom，对齐 oauth2-jose 6.5.7 自声明的 9.37.4，同 aliyun-java-sdk-core/tika-core 先例）。
- jacoco 门禁 INSTRUCTION ≥ 0.80 / LINE ≥ 0.80 / BRANCH ≥ 0.70（verify 阶段）；dependency:analyze failOnWarning（豁免须带理由注释，样式同 mail/llm pom）。
- ArchUnit 三规则：包无环；autoconfigure 不被主包依赖；core 依赖白名单（允许 spring-security-core / spring-security-oauth2-jose，禁 jakarta.servlet / spring-security-web / spring-security-config / oauth2-resource-server，spec §4.1）。
- 测试风格：JUnit 5 + AssertJ，断言带中文 `.as(...)`；类/方法 Javadoc 中文，引用 spec 章节号（如 "07 §4.2"）。
- 提交信息 `auth-enhancement: 中文摘要`（父 pom 接线也用此前缀）；**小步提交**（长任务恢复依赖已落提交——过程教训，账本 M0-M4 段）。
- ⚠️ **工作区有并行会话的未提交文件**（`README.md`、`docs/design/00-overview.md`、`docs/design/06-docs-enhancement.md`）：只 `git add` 各步骤明确列出的路径，**严禁** `git add -A` / `git add .` / `git commit -a`。
- Shell 为 Windows PowerShell；多测试类 `-Dtest=A,B`（逗号分隔）；模块内跑测：`mvn -pl auth-enhancement test`。
- 分支 `feat/auth-enhancement-p1`（Task 1 步骤 0 创建，基于 master @ 85b5f4b 或其后）。

## 裁定速查（spec §2.4，实现语义依据）

- **R1**：P1 仅资源服务器；无 oauth2-client 相关代码。
- **R2**：单 realm 单 issuer；不留多 issuer 死桩。
- **R3**：`AuthContext` 门面为正门；`SessionUserHolder` 桥接默认关（`toolbox.auth.bridge.session-user.enabled=false`）。
- **R4**：`toolbox.auth.*` 自有配置域；本地派生端点；自建 `JwtDecoder`。
- **R5**：引 jar + enabled + 缺 `server-url`/`realm` ⇒ 启动失败，消息带最小配置样例；JWKS 懒加载，启动零网络。

---

### Task 1: 模块骨架 + 父 pom 接线 + KeycloakEndpoints

**Files:**
- Modify: `pom.xml`（根：`<module>` + testcontainers-keycloak pin）
- Create: `auth-enhancement/pom.xml`
- Create: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/jwt/KeycloakEndpoints.java`
- Test: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/jwt/KeycloakEndpointsTest.java`

**Interfaces:**
- Produces: `KeycloakEndpoints.issuer(String serverUrl, String realm) -> String`；`KeycloakEndpoints.jwksUri(String serverUrl, String realm) -> String`。两者对 null/空白/畸形 URL 抛 `IllegalStateException`，消息含配置键名与最小 yaml 样例（R5 的校验源头，Task 3/5 复用）。

- [ ] **Step 0: 创建分支并确认工作区边界**

```powershell
git checkout master; git checkout -b feat/auth-enhancement-p1; git status --short
```

预期 `git status` 显示并行会话的 ` M README.md`、` M docs/design/00-overview.md`、`?? docs/design/06-docs-enhancement.md` 等——**不要动它们**。

- [ ] **Step 1: 父 pom 接线**

根 `pom.xml` 的 `<modules>` 内、`database-enhancement` 之前加：

```xml
        <module>auth-enhancement</module>
```

`<properties>` 内 `tika.version` 之后加：

```xml
        <!-- auth-enhancement 真 Keycloak 集成测试（test-only）；Boot BOM 不管理 com.github.dasniko，
             4.1.1 默认对齐 Keycloak 26.5（其传递的 keycloak-admin-client 仅 test 作用域，
             不构成运行时 KC 依赖，07 §7）。 -->
        <testcontainers-keycloak.version>4.1.1</testcontainers-keycloak.version>
        <!-- auth-enhancement 测试铸 token（test-only）；spring-security-oauth2-jose 以 compile
             传递引入 nimbus-jose-jwt，但 Boot BOM 与 spring-security-bom 均不管理该坐标
             （.m2 实测核验）——测试代码直接使用其 API（RSAKey/RSASSASigner/SignedJWT），
             依赖账目守卫要求显式声明，版本收口于此，对齐 oauth2-jose 6.5.7 自身声明的
             传递版本，避免漂移（同 aliyun-java-sdk-core / tika-core 先例）。 -->
        <nimbus-jose-jwt.version>9.37.4</nimbus-jose-jwt.version>
```

`<dependencyManagement>` 内 archunit 条目之后加：

```xml
            <dependency>
                <groupId>com.github.dasniko</groupId>
                <artifactId>testcontainers-keycloak</artifactId>
                <version>${testcontainers-keycloak.version}</version>
            </dependency>
            <dependency>
                <groupId>com.nimbusds</groupId>
                <artifactId>nimbus-jose-jwt</artifactId>
                <version>${nimbus-jose-jwt.version}</version>
            </dependency>
```

- [ ] **Step 2: 模块 pom**

创建 `auth-enhancement/pom.xml`（依赖账目严格对齐 07 §7；analyze 豁免注释样式同 mail/llm）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>cn.code91</groupId>
        <artifactId>optional-tool-box</artifactId>
        <version>1.0-SNAPSHOT</version>
    </parent>

    <artifactId>auth-enhancement</artifactId>

    <dependencies>
        <dependency>
            <groupId>cn.code91</groupId>
            <artifactId>server-facility</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <!-- 装配类直接使用 @EnableConfigurationProperties/@ConfigurationProperties/@DefaultValue（spring-boot）、
             @Bean/@Configuration（spring-context）、ObjectProvider 不用但 Duration 绑定与 Ordered 等
             （spring-core）；同 compare/storage/mail 教训：仅靠传递引入会被 dependency:analyze 判
             "直接使用未声明"。 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-beans</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-core</artifactId>
        </dependency>

        <!-- 鉴权引擎：optional=true，消费方自行引 starter（00 依赖治理四层；07 §7/§8）。
             starter 是交付物标记；下方逐件声明实际内容依赖（字节码直接引用处见各注释）。 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
            <optional>true</optional>
        </dependency>
        <!-- SecurityContextHolder/Authentication/GrantedAuthority（core 门面与 converter）。 -->
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-core</artifactId>
            <optional>true</optional>
        </dependency>
        <!-- HttpSecurity/SessionCreationPolicy/Customizer/@EnableMethodSecurity（装配层）。 -->
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-config</artifactId>
            <optional>true</optional>
        </dependency>
        <!-- SecurityFilterChain/AuthenticationEntryPoint/AccessDeniedHandler/AuthorizationFilter（web 翼）。 -->
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-web</artifactId>
            <optional>true</optional>
        </dependency>
        <!-- OAuth2AuthenticationException/OAuth2Error（EntryPoint 错误码提取）。 -->
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-oauth2-core</artifactId>
            <optional>true</optional>
        </dependency>
        <!-- Jwt/JwtDecoder/NimbusJwtDecoder/JwtValidators/OAuth2TokenValidator 装配（jwt 包 + core 值类型）。 -->
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-oauth2-jose</artifactId>
            <optional>true</optional>
        </dependency>
        <!-- JwtAuthenticationToken/BearerTokenAuthenticationFilter 位点（converter 产物与链装配）。 -->
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-oauth2-resource-server</artifactId>
            <optional>true</optional>
        </dependency>
        <!-- EntryPoint/DeniedHandler/BridgeFilter 直接使用 HttpServletRequest/Response 与 Filter；
             消费方经 starter-web 获得实现，模块侧仅编译面（07 §4.3：web 翼不引 spring-web）。 -->
        <dependency>
            <groupId>jakarta.servlet</groupId>
            <artifactId>jakarta.servlet-api</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-test</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- MockHttpServletRequest/Response 与 MockMvc 相关类型直接 import（spring-test 为
             starter-test 实际内容件，同 mail 对 spring-boot-test 的显式声明先例）。 -->
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter-api</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.assertj</groupId>
            <artifactId>assertj-core</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- jwt()/springSecurity() 测试后处理器（07 §9）。 -->
        <dependency>
            <groupId>org.springframework.security</groupId>
            <artifactId>spring-security-test</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- 全链测试宿主：测试用 @SpringBootApplication + @RestController 需要 MVC/内嵌容器聚合；
             注解本体（@RestController/@GetMapping）在 spring-web，测试代码直接 import 需另行声明。 -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- Task 5 实施实测修正（原 test scope）：装配层链代码的编译期签名解析需要 spring-web——
             requestMatchers 重载消歧触 HttpMethod、AuthorizationFilter extends GenericFilterBean
             （javap 核验字节码不含直接引用，纯编译面）；消费方 servlet 应用经 starter-web 必有，
             故与 security 系可选件同型 optional 声明；web 包实现类仍不引 spring-web（07 §4.3
             约束的对象是实现类，SessionUserBridgeFilter 保持 jakarta Filter）。 -->
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-web</artifactId>
            <optional>true</optional>
        </dependency>
        <!-- 假 JWKS 端点（07 §9 无 Docker 主矩阵）；jetty12 变体经 ServiceLoader 装配，
             同 llm 先例（其 pom 注释详述）。 -->
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- Task 6 实施实测修正（同 llm 先例同因）：本模块 test classpath 经 starter-web 带入
             Jetty 12 系（Boot 3.5 管理），wiremock 主件内嵌 Jetty 11 起服务器会冲突失败；
             wiremock-jetty12 经 ServiceLoader 提供 HttpServerFactory 适配，字节码不直接引用，
             需 analyze 豁免（见下方 ignoredUnusedDeclaredDependencies）。 -->
        <dependency>
            <groupId>org.wiremock</groupId>
            <artifactId>wiremock-jetty12</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- 测试铸 token（RSAKey/RSASSASigner/SignedJWT）；运行期 nimbus 经 oauth2-jose 传递，
             测试代码直接 import 需显式声明（版本由父 pom pin——Boot BOM 与 spring-security-bom
             均不管理该坐标，Task 1 实测修正，对齐 oauth2-jose 自声明传递版本 9.37.4）。 -->
        <dependency>
            <groupId>com.nimbusds</groupId>
            <artifactId>nimbus-jose-jwt</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- 真 Keycloak IT（07 §9）；容器 API 直接使用两件均需声明。 -->
        <dependency>
            <groupId>com.github.dasniko</groupId>
            <artifactId>testcontainers-keycloak</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.tngtech.archunit</groupId>
            <artifactId>archunit-junit5</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.tngtech.archunit</groupId>
            <artifactId>archunit</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-classic</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>ch.qos.logback</groupId>
            <artifactId>logback-core</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-dependency-plugin</artifactId>
                <executions>
                    <execution>
                        <id>analyze-only</id>
                        <configuration>
                            <failOnWarning>true</failOnWarning>
                            <ignoredUnusedDeclaredDependencies>
                                <!-- 父 pom 既有两条聚合 artifact 误报（完整重列，避免依赖插件配置合并顺序）。 -->
                                <ignoredUnusedDeclaredDependency>org.springframework.boot:spring-boot-starter-test</ignoredUnusedDeclaredDependency>
                                <ignoredUnusedDeclaredDependency>com.tngtech.archunit:archunit-junit5</ignoredUnusedDeclaredDependency>
                                <!-- starter-oauth2-resource-server：聚合 starter 本身不被字节码引用
                                     （实际内容件已逐一显式声明），保留声明是 07 §7 的显式交付物
                                     （消费方按 §8 引它即得全套引擎），同 starter-mail 先例。 -->
                                <ignoredUnusedDeclaredDependency>org.springframework.boot:spring-boot-starter-oauth2-resource-server</ignoredUnusedDeclaredDependency>
                                <!-- starter-web（test）：为测试宿主提供 MVC+内嵌容器聚合，测试字节码
                                     直接引用的是 spring-web/spring-test（已另行声明）。 -->
                                <ignoredUnusedDeclaredDependency>org.springframework.boot:spring-boot-starter-web</ignoredUnusedDeclaredDependency>
                                <!-- spring-web：装配层链代码纯编译期签名解析需求（HttpMethod 重载消歧 +
                                     AuthorizationFilter 父类 GenericFilterBean），字节码无直接引用，
                                     analyze 天然看不到——同 jakarta.servlet-api 编译面依赖豁免模式
                                     （Task 5 实测修正）。 -->
                                <ignoredUnusedDeclaredDependency>org.springframework:spring-web</ignoredUnusedDeclaredDependency>
                                <!-- wiremock-jetty12：ServiceLoader 装配的 HttpServerFactory 扩展，
                                     字节码层面不会被直接引用类名（同 llm 模块先例注释）。 -->
                                <ignoredUnusedDeclaredDependency>org.wiremock:wiremock-jetty12</ignoredUnusedDeclaredDependency>
                            </ignoredUnusedDeclaredDependencies>
                        </configuration>
                    </execution>
                </executions>
            </plugin>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-surefire-plugin</artifactId>
                <configuration>
                    <!-- 同 llm 模块实测踩坑（其 pom 有完整因果注释）：部分 Windows 机器 %TEMP% 为
                         8.3 短路径（含 "~"）时 JDK 21 NIO Selector 的 AF_UNIX 自唤醒管道 connect()
                         失败，WireMock 内嵌服务器无法启动；显式指向 target/tmp 规避，
                         对不受影响机器无副作用。 -->
                    <argLine>@{argLine} -XX:+EnableDynamicAgentLoading -Djdk.net.unixdomain.tmpdir=${project.build.directory}/tmp</argLine>
                </configuration>
            </plugin>
        </plugins>
    </build>

</project>
```

- [ ] **Step 3: 写 KeycloakEndpoints 失败测试**

创建 `KeycloakEndpointsTest.java`：

```java
package cn.code91.toolbox.auth.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Keycloak 端点本地派生（07 §4.2）：issuer/jwks 由 server-url + realm 纯函数拼出，
 * 启动零网络请求（R5）；对"真配置错误"抛带引导的 IllegalStateException（R5 fail-fast 源头）。
 */
@DisplayName("KeycloakEndpoints 端点派生")
class KeycloakEndpointsTest {

    @Test
    void derivesIssuerAndJwks() {
        assertThat(KeycloakEndpoints.issuer("https://kc.example.com", "myrealm"))
                .as("KC 17+ 布局：{server}/realms/{realm}")
                .isEqualTo("https://kc.example.com/realms/myrealm");
        assertThat(KeycloakEndpoints.jwksUri("https://kc.example.com", "myrealm"))
                .as("JWKS 为 issuer 下稳定路径")
                .isEqualTo("https://kc.example.com/realms/myrealm/protocol/openid-connect/certs");
    }

    @Test
    void trimsTrailingSlashAndKeepsLegacyPrefix() {
        assertThat(KeycloakEndpoints.issuer("https://kc.example.com/", "r"))
                .as("尾斜杠归一，避免双斜杠 issuer 不匹配")
                .isEqualTo("https://kc.example.com/realms/r");
        assertThat(KeycloakEndpoints.issuer("https://kc.example.com/auth", "r"))
                .as("遗留发行版 /auth 前缀并入 server-url 即可（07 §4.2）")
                .isEqualTo("https://kc.example.com/auth/realms/r");
    }

    @Test
    void failsFastWithGuidanceOnMissingOrMalformedConfig() {
        assertThatThrownBy(() -> KeycloakEndpoints.issuer(null, "r"))
                .as("缺 server-url：R5 启动期失败，消息带配置键与样例")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toolbox.auth.keycloak.server-url");
        assertThatThrownBy(() -> KeycloakEndpoints.issuer("https://kc.example.com", " "))
                .as("缺 realm")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toolbox.auth.keycloak.realm");
        assertThatThrownBy(() -> KeycloakEndpoints.issuer("not a url", "r"))
                .as("畸形 URL 属真配置错误")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server-url");
        assertThatThrownBy(() -> KeycloakEndpoints.issuer("ftp://kc.example.com", "r"))
                .as("非 http(s) scheme 拒绝")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("http");
    }
}
```

- [ ] **Step 4: 跑测确认失败**

```powershell
mvn -q -pl auth-enhancement test "-Dtest=KeycloakEndpointsTest"
```

预期：编译失败（`KeycloakEndpoints` 不存在）。

- [ ] **Step 5: 实现 KeycloakEndpoints**

```java
package cn.code91.toolbox.auth.jwt;

import java.net.URI;

/**
 * Keycloak 端点本地派生（07 §4.2，纯函数）。issuer = {server-url 去尾斜杠}/realms/{realm}
 * （KC 17+ Quarkus 发行版布局；遗留 /auth 前缀由消费方并入 server-url）；jwks 为 issuer 下
 * 稳定公开契约路径。<b>不做任何网络请求</b>——R5：fail-fast 只对真配置错误（缺失/畸形），
 * KC 可达性问题留给运行期懒加载（07 §5.4）。
 */
public final class KeycloakEndpoints {

    private static final String CONFIG_GUIDANCE = """
            ；auth-enhancement 需要最小配置：
            toolbox:
              auth:
                keycloak:
                  server-url: https://kc.example.com
                  realm: myrealm
            （暂不启用请设 toolbox.auth.enabled=false，07 §5.4）""";

    private KeycloakEndpoints() {
    }

    /** {@return issuer 地址}（用于 iss 校验与 USAGE 示例） */
    public static String issuer(String serverUrl, String realm) {
        String base = validateServerUrl(serverUrl);
        if (realm == null || realm.isBlank()) {
            throw new IllegalStateException("缺少 toolbox.auth.keycloak.realm" + CONFIG_GUIDANCE);
        }
        return base + "/realms/" + realm.trim();
    }

    /** {@return JWKS 地址}（decoder 懒加载取签名公钥） */
    public static String jwksUri(String serverUrl, String realm) {
        return issuer(serverUrl, realm) + "/protocol/openid-connect/certs";
    }

    private static String validateServerUrl(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            throw new IllegalStateException("缺少 toolbox.auth.keycloak.server-url" + CONFIG_GUIDANCE);
        }
        String trimmed = serverUrl.trim();
        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "toolbox.auth.keycloak.server-url 不是合法 URL：\"" + trimmed + "\"" + CONFIG_GUIDANCE, e);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null) {
            throw new IllegalStateException(
                    "toolbox.auth.keycloak.server-url 必须是 http(s) 绝对地址：\"" + trimmed + "\"" + CONFIG_GUIDANCE);
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
```

注意：`URI.create("not a url")` 对含空格字符串会抛 `IllegalArgumentException`（被包装），但 `URI.create("notaurl")` 能解析成无 scheme URI——由 scheme/host 检查兜住。两条测试分别覆盖这两条路径。

- [ ] **Step 6: 跑测确认通过 + 提交**

```powershell
mvn -q -pl auth-enhancement test "-Dtest=KeycloakEndpointsTest"
```

预期：`Tests run: 3, Failures: 0`。

```powershell
git add pom.xml auth-enhancement/pom.xml auth-enhancement/src
git commit -m "auth-enhancement: 模块骨架、父 pom 接线与 KeycloakEndpoints 端点派生"
```

---

### Task 2: core 包——KeycloakClaims / CurrentUser / AuthContext

**Files:**
- Create: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/core/KeycloakClaims.java`
- Create: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/core/CurrentUser.java`
- Create: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/core/AuthContext.java`
- Test: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/core/KeycloakClaimsTest.java`
- Test: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/core/AuthContextTest.java`

**Interfaces:**
- Consumes: 无（core 是最底层包；仅 spring-security-core + oauth2-jose 类型）。
- Produces（Task 3/4/6 依赖）：
  - `KeycloakClaims.realmRoles(Jwt) -> Set<String>`；`KeycloakClaims.clientRoles(Jwt) -> Map<String, Set<String>>`；`KeycloakClaims.scopes(Jwt) -> Set<String>`。全部空安全（缺 claim 返回空集合），返回不可变集合。
  - `CurrentUser(String sub, String username, String email, String name, Set<String> realmRoles, Map<String, Set<String>> clientRoles, Map<String, Object> rawClaims)` record + 静态工厂 `CurrentUser.from(Jwt jwt, String username) -> CurrentUser`。
  - `AuthContext.current() -> Optional<CurrentUser>`；`AuthContext.isAuthenticated() -> boolean`；`AuthContext.hasRole(String) -> boolean`；`AuthContext.hasClientRole(String, String) -> boolean`。读 `SecurityContextHolder`，判 `getPrincipal() instanceof Jwt`（07 §4.1 钉死：不触 `JwtAuthenticationToken`）。

**结构注**：`KeycloakClaims` 是对 spec §4 结构图的微小补充（设计呈现后发现 converter 与 CurrentUser 适配共享同一套空安全嵌套 claim 导航，放 core 供 jwt 包单向依赖）——已在 07 文档演进时无需回改，属计划级实现细节。

- [ ] **Step 1: 写 KeycloakClaims 失败测试**

```java
package cn.code91.toolbox.auth.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keycloak 私有 claim 结构解析（07 §4.2 表）：realm_access.roles / resource_access.{client}.roles
 * / scope。全程空安全——KC 可通过 mapper 配置剥除任一结构（07 §4.2"缺 claim 空安全"）。
 */
@DisplayName("KeycloakClaims 解析")
class KeycloakClaimsTest {

    /** 典型 KC access token claim 形态夹具（真实结构，07 §9 单测行） */
    private static Jwt kcJwt() {
        return baseJwt()
                .claim("realm_access", Map.of("roles", List.of("order-admin", "uma_authorization")))
                .claim("resource_access", Map.of(
                        "toolbox-api", Map.of("roles", List.of("doc-reader")),
                        "account", Map.of("roles", List.of("view-profile"))))
                .claim("scope", "openid profile email")
                .build();
    }

    private static Jwt.Builder baseJwt() {
        return Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject("2f0c3f9a-0000-0000-0000-000000000001")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
    }

    @Test
    void parsesRealmClientRolesAndScopes() {
        Jwt jwt = kcJwt();
        assertThat(KeycloakClaims.realmRoles(jwt))
                .as("realm_access.roles 全量").containsExactlyInAnyOrder("order-admin", "uma_authorization");
        assertThat(KeycloakClaims.clientRoles(jwt))
                .as("resource_access 全 client（07 §4.1：CurrentUser 不受映射白名单限制）")
                .containsOnlyKeys("toolbox-api", "account");
        assertThat(KeycloakClaims.clientRoles(jwt).get("toolbox-api")).containsExactly("doc-reader");
        assertThat(KeycloakClaims.scopes(jwt))
                .as("scope 空格分隔").containsExactlyInAnyOrder("openid", "profile", "email");
    }

    @Test
    void missingClaimsYieldEmptyCollections() {
        Jwt bare = baseJwt().claim("dummy", "x").build();
        assertThat(KeycloakClaims.realmRoles(bare)).as("缺 realm_access 空安全").isEmpty();
        assertThat(KeycloakClaims.clientRoles(bare)).as("缺 resource_access 空安全").isEmpty();
        assertThat(KeycloakClaims.scopes(bare)).as("缺 scope 空安全").isEmpty();
    }

    @Test
    void malformedStructuresYieldEmptyCollections() {
        Jwt weird = baseJwt()
                .claim("realm_access", "not-a-map")
                .claim("resource_access", Map.of("c1", "not-a-map", "c2", Map.of("roles", "not-a-list")))
                .claim("scope", 42)
                .build();
        assertThat(KeycloakClaims.realmRoles(weird)).as("结构畸形不抛，按缺失处理").isEmpty();
        assertThat(KeycloakClaims.clientRoles(weird)).isEmpty();
        assertThat(KeycloakClaims.scopes(weird)).isEmpty();
    }
}
```

- [ ] **Step 2: 跑测确认编译失败**

```powershell
mvn -q -pl auth-enhancement test "-Dtest=KeycloakClaimsTest"
```

- [ ] **Step 3: 实现 KeycloakClaims**

```java
package cn.code91.toolbox.auth.core;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keycloak 私有 claim 结构的空安全导航（07 §4.2）。KC 把 realm 角色放在
 * {@code realm_access.roles}、client 角色放在 {@code resource_access.{client-id}.roles}
 * （皆非 OAuth2 标准 claim）；任一结构可被 KC 侧 mapper 配置剥除或改形，故全部按
 * "缺失/畸形 ⇒ 空集合"处理，绝不抛异常。返回集合均不可变。
 */
public final class KeycloakClaims {

    private KeycloakClaims() {
    }

    /** {@return realm_access.roles 原始角色名集合}（未加 ROLE_ 前缀） */
    public static Set<String> realmRoles(Jwt jwt) {
        return rolesOf(jwt.getClaim("realm_access"));
    }

    /** {@return resource_access 下全部 client → 角色集}（07 §4.1：读原始结构，不受映射白名单限制） */
    public static Map<String, Set<String>> clientRoles(Jwt jwt) {
        Object resourceAccess = jwt.getClaim("resource_access");
        if (!(resourceAccess instanceof Map<?, ?> byClient)) {
            return Map.of();
        }
        Map<String, Set<String>> result = new LinkedHashMap<>();
        byClient.forEach((client, access) -> {
            Set<String> roles = rolesOf(access);
            if (client instanceof String clientId && !roles.isEmpty()) {
                result.put(clientId, roles);
            }
        });
        return Map.copyOf(result);
    }

    /** {@return scope claim 按空格拆分的集合}（标准 OAuth2 语义，Spring SCOPE_ 映射的数据源） */
    public static Set<String> scopes(Jwt jwt) {
        Object scope = jwt.getClaim("scope");
        if (!(scope instanceof String s) || s.isBlank()) {
            return Set.of();
        }
        Set<String> scopes = new LinkedHashSet<>();
        for (String item : s.trim().split("\\s+")) {
            scopes.add(item);
        }
        return Set.copyOf(scopes);
    }

    private static Set<String> rolesOf(Object accessEntry) {
        if (!(accessEntry instanceof Map<?, ?> access) || !(access.get("roles") instanceof Collection<?> roles)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object role : roles) {
            if (role instanceof String r && !r.isBlank()) {
                result.add(r);
            }
        }
        return Set.copyOf(result);
    }
}
```

- [ ] **Step 4: 跑测确认通过，提交**

```powershell
mvn -q -pl auth-enhancement test "-Dtest=KeycloakClaimsTest"
git add auth-enhancement/src
git commit -m "auth-enhancement: KeycloakClaims 空安全 claim 导航"
```

- [ ] **Step 5: 写 AuthContext + CurrentUser 失败测试**

```java
package cn.code91.toolbox.auth.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AuthContext 静态门面（07 §4.1）：读 SecurityContextHolder，principal instanceof Jwt 才适配；
 * username 取 authentication.getName()（converter 已按 principal-claim 解析）。
 */
@DisplayName("AuthContext 门面")
class AuthContextTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Jwt kcJwt() {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .subject("sub-1")
                .claim("email", "alice@example.com")
                .claim("name", "Alice Doe")
                .claim("realm_access", Map.of("roles", List.of("order-admin")))
                .claim("resource_access", Map.of("toolbox-api", Map.of("roles", List.of("doc-reader"))))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    @Test
    void emptyWhenNoAuthentication() {
        assertThat(AuthContext.current()).as("无认证：空而非异常").isEmpty();
        assertThat(AuthContext.isAuthenticated()).isFalse();
        assertThat(AuthContext.hasRole("order-admin")).isFalse();
    }

    @Test
    void emptyWhenPrincipalIsNotJwt() {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("plain-user", "n/a"));
        assertThat(AuthContext.current()).as("非 JWT 主体（如测试桩）不适配").isEmpty();
    }

    @Test
    void adaptsJwtPrincipalToCurrentUser() {
        TestingAuthenticationToken auth = new TestingAuthenticationToken(kcJwt(), "n/a");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        CurrentUser user = AuthContext.current().orElseThrow();
        assertThat(user.sub()).isEqualTo("sub-1");
        assertThat(user.username()).as("桩 token 的 getName() 回落 toString，仅断言非空；" +
                "真实链路 converter 产 JwtAuthenticationToken(principalName)，username=alice 由全链测试钉").isNotBlank();
        assertThat(user.email()).isEqualTo("alice@example.com");
        assertThat(user.name()).isEqualTo("Alice Doe");
        assertThat(user.realmRoles()).containsExactly("order-admin");
        assertThat(user.clientRoles()).containsOnlyKeys("toolbox-api");
        assertThat(user.rawClaims()).as("完整 claim 逃生舱").containsKey("realm_access");

        assertThat(AuthContext.isAuthenticated()).isTrue();
        assertThat(AuthContext.hasRole("order-admin")).as("realm 角色原始名，不带 ROLE_ 前缀").isTrue();
        assertThat(AuthContext.hasRole("other")).isFalse();
        assertThat(AuthContext.hasClientRole("toolbox-api", "doc-reader")).isTrue();
        assertThat(AuthContext.hasClientRole("toolbox-api", "x")).isFalse();
        assertThat(AuthContext.hasClientRole("nope", "doc-reader")).isFalse();
    }

    @Test
    void nullValuedClaimAndNullArgumentsAreNeverErrors() {
        // Task 2 审查修正回归钉：JSON null 经解码可合法落入 claims；null 入参走"无匹配"语义。
        Jwt withNullClaim = Jwt.withTokenValue("t").header("alg", "RS256").subject("sub-2")
                .claim("preferred_username", "bob")
                .claim("custom_nullable", null)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        TestingAuthenticationToken auth = new TestingAuthenticationToken(withNullClaim, "n/a");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        CurrentUser user = AuthContext.current()
                .orElseThrow(() -> new AssertionError("null 值 claim 不得使适配失败（07 §3 常态而非错误）"));
        assertThat(user.rawClaims()).as("null 值 claim 视同缺失剔除").doesNotContainKey("custom_nullable");
        assertThat(AuthContext.hasRole(null)).as("null 角色名恒 false 而非 NPE").isFalse();
        assertThat(AuthContext.hasClientRole(null, "r")).isFalse();
        assertThat(AuthContext.hasClientRole("c", null)).isFalse();
    }

    @Test
    void usernameFallsBackToSubWhenBlank() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("sub-3")
                .claim("dummy", "x")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(CurrentUser.from(jwt, " ").username())
                .as("username 空白回落 sub（07 §4.1）").isEqualTo("sub-3");
        assertThat(CurrentUser.from(jwt, null).username()).isEqualTo("sub-3");
    }
}
```

- [ ] **Step 6: 跑测确认编译失败**

```powershell
mvn -q -pl auth-enhancement test "-Dtest=AuthContextTest"
```

- [ ] **Step 7: 实现 CurrentUser 与 AuthContext**

`CurrentUser.java`：

```java
package cn.code91.toolbox.auth.core;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 已认证的 Keycloak 用户视图（07 §4.1）。字段全部来自 access token claim；
 * {@code clientRoles} 读 {@code resource_access} 原始全量（不受 {@code toolbox.auth.jwt.client-roles}
 * 映射白名单限制——白名单只约束 GrantedAuthority 映射，见 07 §4.2 跨 client 重名披露）。
 *
 * @param sub        KC 用户 UUID（稳定主键）
 * @param username   principal 名（converter 已按 principal-claim 解析，缺失回落 sub）
 * @param email      email claim，可空
 * @param name       name claim，可空
 * @param realmRoles realm 角色原始名（未加 ROLE_ 前缀），不可变
 * @param clientRoles client-id → 角色集（resource_access 全量），不可变
 * @param rawClaims  完整 claim 逃生舱，不可变
 */
public record CurrentUser(
        String sub,
        String username,
        String email,
        String name,
        Set<String> realmRoles,
        Map<String, Set<String>> clientRoles,
        Map<String, Object> rawClaims) {

    public CurrentUser {
        realmRoles = realmRoles == null ? Set.of() : Set.copyOf(realmRoles);
        clientRoles = clientRoles == null ? Map.of() : Map.copyOf(clientRoles);
        rawClaims = rawClaims == null ? Map.of() : Map.copyOf(rawClaims);
    }

    /**
     * 从 Jwt 适配（username 由调用方给定——AuthContext 用 authentication.getName()）。
     * null 值 claim 视同缺失剔除（Task 2 审查修正：Map.copyOf 拒绝 null 值，JSON null 经
     * 解码可合法出现在 claims 中；与 KeycloakClaims 的空安全语义一致）。
     */
    public static CurrentUser from(Jwt jwt, String username) {
        Map<String, Object> raw = new LinkedHashMap<>();
        jwt.getClaims().forEach((k, v) -> {
            if (v != null) {
                raw.put(k, v);
            }
        });
        return new CurrentUser(
                jwt.getSubject(),
                username == null || username.isBlank() ? jwt.getSubject() : username,
                jwt.getClaimAsString("email"),
                jwt.getClaimAsString("name"),
                KeycloakClaims.realmRoles(jwt),
                KeycloakClaims.clientRoles(jwt),
                raw);
    }
}
```

`AuthContext.java`：

```java
package cn.code91.toolbox.auth.core;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Optional;
import java.util.Set;

/**
 * 当前用户静态门面（07 §4.1，R3 正门）。读 {@link SecurityContextHolder}，
 * {@code getPrincipal() instanceof Jwt} 时按需适配为 {@link CurrentUser}（无缓存：record
 * 构建成本低，不值得引入请求级缓存复杂度）。<b>依赖面钉死</b>：只触 spring-security-core 与
 * oauth2-jose 的 {@code Jwt}，不触 {@code JwtAuthenticationToken}（resource-server 类型）——
 * ArchUnit 第三条规则据此收窄（07 §4.1）。
 */
public final class AuthContext {

    private AuthContext() {
    }

    /** {@return 当前请求的已认证用户}；未认证/非 JWT 主体返回 empty（常态而非错误，07 §3 豁免） */
    public static Optional<CurrentUser> current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Optional.empty();
        }
        return Optional.of(CurrentUser.from(jwt, auth.getName()));
    }

    /** {@return 当前请求是否携带已认证的 JWT 主体} */
    public static boolean isAuthenticated() {
        return current().isPresent();
    }

    /** {@return 是否拥有指定 realm 角色}（原始名，不带 ROLE_ 前缀；null 入参恒 false——不可变集合 contains(null) 抛 NPE，Task 2 审查修正） */
    public static boolean hasRole(String realmRole) {
        if (realmRole == null) {
            return false;
        }
        return current().map(u -> u.realmRoles().contains(realmRole)).orElse(false);
    }

    /** {@return 是否拥有指定 client 的角色}（读 resource_access 原始结构，无跨 client 重名歧义；null 入参恒 false，同上） */
    public static boolean hasClientRole(String clientId, String role) {
        if (clientId == null || role == null) {
            return false;
        }
        return current()
                .map(u -> u.clientRoles().getOrDefault(clientId, Set.of()).contains(role))
                .orElse(false);
    }
}
```

- [ ] **Step 8: 跑测确认通过，提交**

```powershell
mvn -q -pl auth-enhancement test "-Dtest=AuthContextTest,KeycloakClaimsTest"
git add auth-enhancement/src
git commit -m "auth-enhancement: core 门面——CurrentUser record 与 AuthContext"
```

---

### Task 3: jwt 包——角色映射 Converter + Decoder 工厂

**Files:**
- Create: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/jwt/KeycloakJwtAuthenticationConverter.java`
- Create: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/jwt/KeycloakJwtDecoderFactory.java`
- Test: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/jwt/KeycloakJwtAuthenticationConverterTest.java`
- Test: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/jwt/KeycloakJwtDecoderFactoryTest.java`

**Interfaces:**
- Consumes: `KeycloakClaims`（Task 2）、`KeycloakEndpoints`（Task 1）。
- Produces（Task 5/6 依赖）:
  - `new KeycloakJwtAuthenticationConverter(boolean mapRealmRoles, List<String> clientRoles, boolean mapScopes, String principalClaim)` 实现 `Converter<Jwt, AbstractAuthenticationToken>`；`convert(Jwt)` 返回带 authorities 与 principal 名的 `JwtAuthenticationToken`。
  - `KeycloakJwtDecoderFactory.create(String serverUrl, String realm, String issuerUriOverride, String requiredAudience, Duration clockSkew, List<String> jwsAlgorithms) -> NimbusJwtDecoder`。配置错误抛 `IllegalStateException`（复用 `KeycloakEndpoints` 校验）。

- [ ] **Step 1: 写 Converter 失败测试**

```java
package cn.code91.toolbox.auth.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keycloak 角色映射（07 §4.2 表）：realm_access → ROLE_、白名单 client → ROLE_（扁平）、
 * scope → SCOPE_；principal-claim 解析与 sub 回落。
 */
@DisplayName("KeycloakJwtAuthenticationConverter 映射")
class KeycloakJwtAuthenticationConverterTest {

    private static Jwt kcJwt() {
        return Jwt.withTokenValue("t").header("alg", "RS256")
                .subject("sub-1")
                .claim("preferred_username", "alice")
                .claim("realm_access", Map.of("roles", List.of("order-admin")))
                .claim("resource_access", Map.of(
                        "toolbox-api", Map.of("roles", List.of("doc-reader")),
                        "account", Map.of("roles", List.of("view-profile"))))
                .claim("scope", "openid profile")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private static List<String> authorityNames(AbstractAuthenticationToken token) {
        return token.getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void defaultsMapRealmRolesAndScopesOnly() {
        var converter = new KeycloakJwtAuthenticationConverter(true, List.of(), true, "preferred_username");
        AbstractAuthenticationToken token = converter.convert(kcJwt());

        assertThat(authorityNames(token))
                .as("默认：realm 角色 + scope；client 角色白名单空不映射（07 §4.2）")
                .containsExactlyInAnyOrder("ROLE_order-admin", "SCOPE_openid", "SCOPE_profile");
        assertThat(token.getName()).as("principal 取 preferred_username").isEqualTo("alice");
    }

    @Test
    void whitelistedClientRolesAreFlattened() {
        var converter = new KeycloakJwtAuthenticationConverter(true, List.of("toolbox-api"), false, "preferred_username");
        assertThat(authorityNames(converter.convert(kcJwt())))
                .as("白名单 client 扁平 ROLE_；account 未列入不映射；map-scopes=false 无 SCOPE_")
                .containsExactlyInAnyOrder("ROLE_order-admin", "ROLE_doc-reader");
    }

    @Test
    void realmRolesCanBeDisabled() {
        var converter = new KeycloakJwtAuthenticationConverter(false, List.of(), true, "preferred_username");
        assertThat(authorityNames(converter.convert(kcJwt())))
                .containsExactlyInAnyOrder("SCOPE_openid", "SCOPE_profile");
    }

    @Test
    void principalFallsBackToSubWhenClaimMissing() {
        var converter = new KeycloakJwtAuthenticationConverter(true, List.of(), true, "preferred_username");
        Jwt noUsername = Jwt.withTokenValue("t").header("alg", "RS256").subject("sub-9")
                .claim("realm_access", Map.of("roles", List.of("r1")))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(converter.convert(noUsername).getName())
                .as("缺 principal-claim 回落 sub（07 §4.2）").isEqualTo("sub-9");
    }

    @Test
    void bareTokenYieldsNoAuthorities() {
        var converter = new KeycloakJwtAuthenticationConverter(true, List.of("toolbox-api"), true, "preferred_username");
        Jwt bare = Jwt.withTokenValue("t").header("alg", "RS256").subject("s")
                .claim("dummy", "x")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        assertThat(converter.convert(bare).getAuthorities()).as("缺全部 claim 空安全").isEmpty();
    }
}
```

- [ ] **Step 2: 跑测确认编译失败**

```powershell
mvn -q -pl auth-enhancement test "-Dtest=KeycloakJwtAuthenticationConverterTest"
```

- [ ] **Step 3: 实现 Converter**

```java
package cn.code91.toolbox.auth.jwt;

import cn.code91.toolbox.auth.core.KeycloakClaims;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Keycloak 私有角色结构 → Spring authorities（07 §4.2）。三路并集：
 * realm_access.roles → {@code ROLE_{role}}（开关 mapRealmRoles）；resource_access 白名单
 * client → {@code ROLE_{role}} 扁平化（跨 client 重名合并——坦诚披露，需区分时业务改用
 * {@code AuthContext.hasClientRole}）；scope → {@code SCOPE_{s}}（保留 Spring 原生语义）。
 * 角色名原始大小写，不转换。principal 名取 principalClaim，缺失回落 sub。
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final boolean mapRealmRoles;
    private final List<String> clientRoleClients;
    private final boolean mapScopes;
    private final String principalClaim;

    public KeycloakJwtAuthenticationConverter(boolean mapRealmRoles, List<String> clientRoleClients,
                                              boolean mapScopes, String principalClaim) {
        this.mapRealmRoles = mapRealmRoles;
        this.clientRoleClients = clientRoleClients == null ? List.of() : List.copyOf(clientRoleClients);
        this.mapScopes = mapScopes;
        this.principalClaim = principalClaim == null || principalClaim.isBlank()
                ? "preferred_username" : principalClaim;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        if (mapRealmRoles) {
            KeycloakClaims.realmRoles(jwt).forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        }
        var clientRoles = KeycloakClaims.clientRoles(jwt);
        for (String client : clientRoleClients) {
            clientRoles.getOrDefault(client, Set.of())
                    .forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));
        }
        if (mapScopes) {
            KeycloakClaims.scopes(jwt).forEach(s -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + s)));
        }
        String principal = jwt.getClaimAsString(principalClaim);
        if (principal == null || principal.isBlank()) {
            principal = jwt.getSubject();
        }
        return new JwtAuthenticationToken(jwt, authorities, principal);
    }
}
```

- [ ] **Step 4: 跑测确认通过，提交**

```powershell
mvn -q -pl auth-enhancement test "-Dtest=KeycloakJwtAuthenticationConverterTest"
git add auth-enhancement/src
git commit -m "auth-enhancement: Keycloak 角色映射 converter（realm/client 白名单/scope 三路并集）"
```

- [ ] **Step 5: 写 DecoderFactory 失败测试**

```java
package cn.code91.toolbox.auth.jwt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Decoder 工厂（07 §5.4）：构建期只做本地校验与端点派生，零网络请求（JWKS 懒加载）；
 * 配置错误经 KeycloakEndpoints 抛带引导的 IllegalStateException（R5）。
 * 校验器组合（issuer/时钟偏移/可选 audience）的行为验证在 Task 6 WireMock 全链完成——
 * 此处只钉"能离线构建"与"fail-fast 传导"。
 */
@DisplayName("KeycloakJwtDecoderFactory 构建")
class KeycloakJwtDecoderFactoryTest {

    @Test
    void buildsOfflineWithoutNetwork() {
        NimbusJwtDecoder decoder = KeycloakJwtDecoderFactory.create(
                "https://kc.invalid.example", "r", null, null, Duration.ofSeconds(60), List.of("RS256"));
        assertThat(decoder).as("指向不可达主机也能构建：启动零网络（R5/07 §5.4）").isNotNull();
    }

    @Test
    void issuerOverrideAndAudienceAccepted() {
        NimbusJwtDecoder decoder = KeycloakJwtDecoderFactory.create(
                "http://kc.internal:8080", "r", "https://kc.public.example/realms/r",
                "toolbox-api", Duration.ofSeconds(30), List.of("RS256", "ES256"));
        assertThat(decoder).isNotNull();
    }

    @Test
    void propagatesConfigErrors() {
        assertThatThrownBy(() -> KeycloakJwtDecoderFactory.create(
                null, "r", null, null, Duration.ofSeconds(60), List.of("RS256")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("toolbox.auth.keycloak.server-url");
        assertThatThrownBy(() -> KeycloakJwtDecoderFactory.create(
                "https://kc.example.com", "r", null, null, Duration.ofSeconds(60), List.of("HS256-bogus")))
                .as("未知签名算法属真配置错误")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jws-algorithms");
    }
}
```

- [ ] **Step 6: 跑测确认编译失败**

```powershell
mvn -q -pl auth-enhancement test "-Dtest=KeycloakJwtDecoderFactoryTest"
```

- [ ] **Step 7: 实现 DecoderFactory**

```java
package cn.code91.toolbox.auth.jwt;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * JwtDecoder 离线构建（07 §5.4）：JWKS 地址本地派生（{@link KeycloakEndpoints}），构建期
 * <b>零网络请求</b>——Nimbus 首个 bearer 请求才取 JWKS（内置缓存 + 未知 kid 自动刷新），
 * KC 暂时不可达不压垮服务启动。校验器：issuer（可 override，内外网地址不一致场景）+
 * 时钟偏移 + 可选 audience（KC 默认 aud 不含 API client-id，须 KC 侧配 mapper 后才开启，
 * 07 §2.2 坑 2）。
 */
public final class KeycloakJwtDecoderFactory {

    private KeycloakJwtDecoderFactory() {
    }

    public static NimbusJwtDecoder create(String serverUrl, String realm, String issuerUriOverride,
                                          String requiredAudience, Duration clockSkew,
                                          List<String> jwsAlgorithms) {
        String jwksUri = KeycloakEndpoints.jwksUri(serverUrl, realm);
        String issuer = issuerUriOverride == null || issuerUriOverride.isBlank()
                ? KeycloakEndpoints.issuer(serverUrl, realm) : issuerUriOverride.trim();

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwksUri)
                .jwsAlgorithms(algs -> resolveAlgorithms(jwsAlgorithms).forEach(algs::add))
                .build();

        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(new JwtTimestampValidator(clockSkew == null ? Duration.ofSeconds(60) : clockSkew));
        validators.add(new JwtIssuerValidator(issuer));
        if (requiredAudience != null && !requiredAudience.isBlank()) {
            String aud = requiredAudience.trim();
            validators.add(new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                    audList -> audList != null && audList.contains(aud)));
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(validators));
        return decoder;
    }

    private static List<SignatureAlgorithm> resolveAlgorithms(List<String> names) {
        List<String> effective = names == null || names.isEmpty() ? List.of("RS256") : names;
        List<SignatureAlgorithm> result = new ArrayList<>();
        for (String name : effective) {
            SignatureAlgorithm alg = SignatureAlgorithm.from(name == null ? null : name.trim());
            if (alg == null) {
                throw new IllegalStateException(
                        "toolbox.auth.jwt.jws-algorithms 含未识别算法：\"" + name
                                + "\"（合法值如 RS256/ES256，须与 Keycloak realm 签名算法一致）");
            }
            result.add(alg);
        }
        return result;
    }
}
```

- [ ] **Step 8: 跑测确认通过，提交**

```powershell
mvn -q -pl auth-enhancement test "-Dtest=KeycloakJwtDecoderFactoryTest"
git add auth-enhancement/src
git commit -m "auth-enhancement: JwtDecoder 离线工厂（JWKS 懒加载 + issuer/时钟/可选 audience 校验）"
```

---

### Task 4: web 包——401/403 响应件 + 会话桥接 + i18n bundle

**Files:**
- Create: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/web/AuthEntryPoint.java`
- Create: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/web/AuthAccessDeniedHandler.java`
- Create: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/web/SessionUserBridgeFilter.java`
- Create: `auth-enhancement/src/main/resources/i18n/toolbox-auth-messages.properties`
- Create: `auth-enhancement/src/main/resources/i18n/toolbox-auth-messages_zh_CN.properties`
- Test: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/web/AuthErrorResponseTest.java`
- Test: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/web/SessionUserBridgeFilterTest.java`

**Interfaces:**
- Consumes: `AuthContext`/`CurrentUser`（Task 2）；facility `BaseResponse.err(int,String)` + `setDescription(String)`、`JsonUtil.serializeUnsafe(Object)`、`LocaleUtil.translateMessageWithFallback(key, args, fallbackPattern, locale)` + `LocaleUtil.getLocale()`、`SessionUserHolder.setUser/clear`、`LogUtil.debug/warn`。
- Produces（Task 5/6 依赖）：`new AuthEntryPoint()` 实现 `AuthenticationEntryPoint`；`new AuthAccessDeniedHandler()` 实现 `AccessDeniedHandler`；`new SessionUserBridgeFilter()` 实现 `jakarta.servlet.Filter`（public，自建 chain 可复用，07 §4.3）。

- [ ] **Step 1: i18n bundle**

`toolbox-auth-messages.properties`：

```properties
toolbox.auth.unauthorized=Authentication required or token invalid
toolbox.auth.forbidden=Access denied
```

`toolbox-auth-messages_zh_CN.properties`（须以 UTF-8 保存，父 pom 编码约定）：

```properties
toolbox.auth.unauthorized=未认证或凭证无效
toolbox.auth.forbidden=权限不足
```

- [ ] **Step 2: 写 401/403 响应件失败测试**

```java
package cn.code91.toolbox.auth.web;

import cn.code91.facility.web.response.BaseResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;

import cn.code91.facility.json.JsonUtil;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 401/403 响应形态（07 §4.3）：facility BaseResponse JSON + RFC 6750 WWW-Authenticate 头；
 * description 只携带安全无害的 OAuth2 错误码，不回显 token 内容与校验内因（防探测）。
 */
@DisplayName("AuthEntryPoint / AuthAccessDeniedHandler 响应形态")
class AuthErrorResponseTest {

    @Test
    void unauthorizedWithOAuth2ErrorCode() throws Exception {
        var response = new MockHttpServletResponse();
        new AuthEntryPoint().commence(new MockHttpServletRequest(), response,
                new OAuth2AuthenticationException(new OAuth2Error("invalid_token", "过期细节不外泄", null)));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate"))
                .as("RFC 6750 头保留标准错误码").isEqualTo("Bearer error=\"invalid_token\"");
        assertThat(response.getContentType()).startsWith("application/json");
        BaseResponse<?> body = JsonUtil.deserialize(response.getContentAsString(), BaseResponse.class)
                .expect("响应体必须是合法 BaseResponse JSON");
        assertThat(body.getCode()).isEqualTo(401);
        assertThat(body.getMessage()).as("i18n 兜底消息非空").isNotBlank();
        assertThat(body.getDescription()).as("description 只带错误码").isEqualTo("invalid_token");
        assertThat(response.getContentAsString())
                .as("不回显异常内因（07 §4.3 防探测）").doesNotContain("过期细节不外泄");
    }

    @Test
    void unauthorizedWithoutBearerToken() throws Exception {
        var response = new MockHttpServletResponse();
        new AuthEntryPoint().commence(new MockHttpServletRequest(), response,
                new InsufficientAuthenticationException("anonymous"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader("WWW-Authenticate"))
                .as("无 token 场景 RFC 6750：裸 Bearer 挑战").isEqualTo("Bearer");
        BaseResponse<?> body = JsonUtil.deserialize(response.getContentAsString(), BaseResponse.class)
                .expect("响应体必须是合法 BaseResponse JSON");
        assertThat(body.getCode()).isEqualTo(401);
        assertThat(body.getDescription()).as("非 OAuth2 异常无错误码").isNull();
    }

    @Test
    void forbiddenShape() throws Exception {
        var response = new MockHttpServletResponse();
        new AuthAccessDeniedHandler().handle(new MockHttpServletRequest(), response,
                new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer error=\"insufficient_scope\"");
        BaseResponse<?> body = JsonUtil.deserialize(response.getContentAsString(), BaseResponse.class)
                .expect("响应体必须是合法 BaseResponse JSON");
        assertThat(body.getCode()).isEqualTo(403);
        assertThat(body.getDescription()).isEqualTo("insufficient_scope");
    }
}
```

> 注：`JsonUtil.deserialize` 返回 `Result`——`.expect(...)` 若 facility `Result` 无此方法名，改用其等价解包 API（写测试前先看 `cn.code91.facility.result.Result` 的公开方法，取"失败即抛断言"的那个）。

- [ ] **Step 3: 跑测确认编译失败**

```powershell
mvn -q -pl auth-enhancement test "-Dtest=AuthErrorResponseTest"
```

- [ ] **Step 4: 实现 EntryPoint / DeniedHandler**

`AuthEntryPoint.java`：

```java
package cn.code91.toolbox.auth.web;

import cn.code91.facility.json.JsonUtil;
import cn.code91.facility.locale.LocaleUtil;
import cn.code91.facility.log.LogUtil;
import cn.code91.facility.web.response.BaseResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/**
 * 401 出口（07 §4.3）：Security Filter 层异常绕过 MVC 全局异常处理器，此处直接写
 * facility {@link BaseResponse} JSON 对齐全仓响应形态；同时保留 RFC 6750
 * {@code WWW-Authenticate} 头不破标准客户端。description 只带 OAuth2 错误码
 * （invalid_token 等），异常内因不回显（防探测）。
 */
public class AuthEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        String errorCode = authException instanceof OAuth2AuthenticationException oauth
                ? oauth.getError().getErrorCode() : null;
        LogUtil.debug("auth-enhancement 401：uri={}，error={}", request.getRequestURI(), errorCode);

        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate",
                errorCode == null ? "Bearer" : "Bearer error=\"" + errorCode + "\"");
        writeJson(response, HttpServletResponse.SC_UNAUTHORIZED,
                "toolbox.auth.unauthorized", "Authentication required or token invalid", errorCode);
    }

    /** 401/403 共用的 BaseResponse JSON 写出（package-private 供 {@link AuthAccessDeniedHandler} 复用） */
    static void writeJson(HttpServletResponse response, int code,
                          String messageKey, String fallback, String description) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        BaseResponse<Void> body = BaseResponse.err(code,
                LocaleUtil.translateMessageWithFallback(messageKey, null, fallback, LocaleUtil.getLocale()));
        body.setDescription(description);
        response.getWriter().write(JsonUtil.serializeUnsafe(body));
    }
}
```

`AuthAccessDeniedHandler.java`：

```java
package cn.code91.toolbox.auth.web;

import cn.code91.facility.log.LogUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/**
 * 403 出口（07 §4.3）：已认证但权限不足。RFC 6750 对应错误码固定
 * {@code insufficient_scope}；响应体形态同 {@link AuthEntryPoint}。
 */
public class AuthAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        LogUtil.debug("auth-enhancement 403：uri={}", request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\"");
        AuthEntryPoint.writeJson(response, HttpServletResponse.SC_FORBIDDEN,
                "toolbox.auth.forbidden", "Access denied", "insufficient_scope");
    }
}
```

- [ ] **Step 5: 跑测确认通过，提交**

```powershell
mvn -q -pl auth-enhancement test "-Dtest=AuthErrorResponseTest"
git add auth-enhancement/src
git commit -m "auth-enhancement: 401/403 BaseResponse 对齐出口与 i18n bundle"
```

- [ ] **Step 6: 写桥接 filter 失败测试**

```java
package cn.code91.toolbox.auth.web;

import cn.code91.facility.web.session.SessionUserHolder;
import cn.code91.toolbox.auth.core.CurrentUser;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SessionUserHolder 桥接（07 §4.3，R3 opt-in）：请求内可见 CurrentUser，请求后必清
 * （finally 语义，与 facility SessionUserClearInterceptor 双清无害）。
 */
@DisplayName("SessionUserBridgeFilter 桥接")
class SessionUserBridgeFilterTest {

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        SessionUserHolder.clear();
    }

    @Test
    void populatesDuringChainAndClearsAfter() throws ServletException, IOException {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "RS256").subject("sub-1")
                .claim("preferred_username", "alice")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        var auth = new TestingAuthenticationToken(jwt, "n/a");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);

        AtomicReference<CurrentUser> seenInChain = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                                   jakarta.servlet.http.HttpServletResponse res) {
                seenInChain.set(SessionUserHolder.getUser(CurrentUser.class).orElse(null));
            }
        });

        new SessionUserBridgeFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(seenInChain.get()).as("链内可经 SessionUserHolder 取到 CurrentUser").isNotNull();
        assertThat(seenInChain.get().sub()).isEqualTo("sub-1");
        assertThat(SessionUserHolder.getUser(CurrentUser.class))
                .as("请求结束必清（ThreadLocal 泄漏防护）").isEmpty();
    }

    @Test
    void anonymousRequestPassesThroughWithoutPopulation() throws ServletException, IOException {
        AtomicReference<Boolean> chainRan = new AtomicReference<>(false);
        MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void service(jakarta.servlet.http.HttpServletRequest req,
                                   jakarta.servlet.http.HttpServletResponse res) {
                chainRan.set(true);
            }
        });
        new SessionUserBridgeFilter().doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);
        assertThat(chainRan.get()).isTrue();
        assertThat(SessionUserHolder.getUser(CurrentUser.class)).isEmpty();
    }
}
```

- [ ] **Step 7: 跑测确认编译失败**

```powershell
mvn -q -pl auth-enhancement test "-Dtest=SessionUserBridgeFilterTest"
```

- [ ] **Step 8: 实现桥接 filter**

```java
package cn.code91.toolbox.auth.web;

import cn.code91.facility.web.session.SessionUserHolder;
import cn.code91.toolbox.auth.core.AuthContext;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;

/**
 * facility {@code SessionUserHolder} 桥接（07 §4.3，R3 opt-in，默认关）：认证完成后把
 * {@link cn.code91.toolbox.auth.core.CurrentUser} 填入 ThreadLocal，兼容存量
 * {@code SessionUserHolder.getUser(...)} 习惯代码；finally 必清（与 facility
 * {@code SessionUserClearInterceptor} 双清无害）。实现为普通 {@link Filter}（非
 * OncePerRequestFilter——那需要 spring-web，07 §4.3 依赖面约束）；set/clear 幂等，
 * forward/error 二次进入无副作用。public：消费方自建 chain（Seam 1）可自行挂载。
 */
public class SessionUserBridgeFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        AuthContext.current().ifPresent(SessionUserHolder::setUser);
        try {
            chain.doFilter(request, response);
        } finally {
            SessionUserHolder.clear();
        }
    }
}
```

- [ ] **Step 9: 跑测确认通过，提交**

```powershell
mvn -q -pl auth-enhancement test "-Dtest=SessionUserBridgeFilterTest"
git add auth-enhancement/src
git commit -m "auth-enhancement: SessionUserHolder opt-in 桥接 filter"
```

---

### Task 5: autoconfigure——Properties + 装配 + 装配矩阵测试 + ArchUnit

**Files:**
- Create: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/autoconfigure/ToolboxAuthProperties.java`
- Create: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/autoconfigure/ToolboxAuthAutoConfiguration.java`
- Create: `auth-enhancement/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/autoconfigure/ToolboxAuthAutoConfigurationTest.java`
- Test: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/architecture/ArchitectureTest.java`

**Interfaces:**
- Consumes: Task 1-4 全部公开类型。
- Produces（Task 6/7 依赖）：装配后的 bean——`JwtDecoder`（bean 名 `toolboxAuthJwtDecoder`）、`KeycloakJwtAuthenticationConverter`（`toolboxAuthJwtAuthenticationConverter`）、`SecurityFilterChain`（`toolboxAuthSecurityFilterChain`）、`ResourceBundleMessageSource`（`toolboxAuthMessageSource`）；配置键全集见 Properties。

- [ ] **Step 1: 实现 Properties（无独立单测，经装配矩阵覆盖）**

```java
package cn.code91.toolbox.auth.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * {@code toolbox.auth.*} 配置（07 §6 全字段）。
 *
 * @param enabled        模块总开关（实际装配由类上 {@code @ConditionalOnProperty(matchIfMissing=true)}
 *                       决定，本字段供配置元数据展示，同 llm 先例）
 * @param keycloak       Keycloak 连接面（必填项缺失 ⇒ 启动失败，R5）
 * @param jwt            claim 映射参数
 * @param securityChain  默认安全链参数
 * @param methodSecurity {@code @EnableMethodSecurity} 开关
 * @param bridge         facility SessionUserHolder 桥接（R3，默认关）
 */
@ConfigurationProperties(prefix = "toolbox.auth")
public record ToolboxAuthProperties(
        boolean enabled,
        @NestedConfigurationProperty Keycloak keycloak,
        @NestedConfigurationProperty Jwt jwt,
        @NestedConfigurationProperty SecurityChain securityChain,
        @NestedConfigurationProperty MethodSecurity methodSecurity,
        @NestedConfigurationProperty Bridge bridge) {

    public ToolboxAuthProperties {
        keycloak = keycloak == null ? new Keycloak(null, null, null, null) : keycloak;
        jwt = jwt == null ? Jwt.defaults() : jwt;
        securityChain = securityChain == null ? new SecurityChain(true, List.of()) : securityChain;
        methodSecurity = methodSecurity == null ? new MethodSecurity(true) : methodSecurity;
        bridge = bridge == null ? new Bridge(new Bridge.SessionUser(false)) : bridge;
    }

    /**
     * @param serverUrl        KC 基地址（必填；遗留 /auth 前缀发行版并入此值，07 §4.2）
     * @param realm            realm 名（必填）
     * @param issuerUri        可选覆写：内外网地址不一致时 issuer 校验用此值，JWKS 仍按 server-url 派生
     * @param requiredAudience 可选；设置才追加 audience 校验器（KC 默认 aud 不含 API client-id，
     *                         须 KC 侧配 mapper，07 §2.2 坑 2）
     */
    public record Keycloak(String serverUrl, String realm, String issuerUri, String requiredAudience) {
    }

    /**
     * @param principalClaim principal 名 claim（缺失回落 sub）
     * @param clockSkew      时间类校验容忍偏移
     * @param jwsAlgorithms  接受的签名算法（须与 KC realm 一致）
     * @param mapRealmRoles  realm_access.roles → ROLE_
     * @param clientRoles    要映射进 authorities 的 client-id 白名单（扁平 ROLE_，重名合并披露见 07 §4.2）
     * @param mapScopes      scope → SCOPE_
     */
    public record Jwt(
            @DefaultValue("preferred_username") String principalClaim,
            @DefaultValue("60s") Duration clockSkew,
            @DefaultValue("RS256") List<String> jwsAlgorithms,
            @DefaultValue("true") boolean mapRealmRoles,
            List<String> clientRoles,
            @DefaultValue("true") boolean mapScopes) {

        public Jwt {
            clientRoles = clientRoles == null ? List.of() : List.copyOf(clientRoles);
        }

        static Jwt defaults() {
            return new Jwt("preferred_username", Duration.ofSeconds(60), List.of("RS256"), true, List.of(), true);
        }
    }

    /**
     * @param enabled     关掉 ⇒ 只留 decoder/converter，链自己写（Seam 1 配置形态，07 §5.3）
     * @param permitPaths 显式放行路径（requestMatchers 模式）；空 = 全鉴权（fail-closed，07 §5.3）
     */
    public record SecurityChain(@DefaultValue("true") boolean enabled, List<String> permitPaths) {

        public SecurityChain {
            permitPaths = permitPaths == null ? List.of() : List.copyOf(permitPaths);
        }
    }

    /** @param enabled {@code @EnableMethodSecurity}（默认开，@PreAuthorize 即用） */
    public record MethodSecurity(@DefaultValue("true") boolean enabled) {
    }

    /** @param sessionUser facility SessionUserHolder 桥接 */
    public record Bridge(@NestedConfigurationProperty SessionUser sessionUser) {

        public Bridge {
            sessionUser = sessionUser == null ? new SessionUser(false) : sessionUser;
        }

        /** @param enabled 默认关（R3：副作用子开关默认关哲学） */
        public record SessionUser(@DefaultValue("false") boolean enabled) {
        }
    }
}
```

- [ ] **Step 2: 实现 AutoConfiguration + imports 文件**

`ToolboxAuthAutoConfiguration.java`：

```java
package cn.code91.toolbox.auth.autoconfigure;

import cn.code91.toolbox.auth.jwt.KeycloakJwtAuthenticationConverter;
import cn.code91.toolbox.auth.jwt.KeycloakJwtDecoderFactory;
import cn.code91.toolbox.auth.web.AuthAccessDeniedHandler;
import cn.code91.toolbox.auth.web.AuthEntryPoint;
import cn.code91.toolbox.auth.web.SessionUserBridgeFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/**
 * auth-enhancement 唯一装配入口（07 §5）。条件分层：L1 探测 oauth2-jose 在场 + Servlet 环境；
 * L2 总开关默认开；L4 Seam 三级——整链（主 Seam）/decoder/converter 任一用户 bean 即退让
 * （07 §5.3）。{@code before} 三个 Boot 官方装配构成退让链：本装配的 {@code JwtDecoder} 使
 * {@code OAuth2ResourceServerAutoConfiguration} 与 {@code UserDetailsServiceAutoConfiguration}
 * 退让，{@code SecurityFilterChain} 使默认 form-login 链退让。R5 fail-fast 由
 * {@code KeycloakJwtDecoderFactory}（经 {@code KeycloakEndpoints}）在 bean 构造期完成——
 * 缺 server-url/realm 阻断容器刷新，异常消息带最小配置样例。
 */
@AutoConfiguration(before = { OAuth2ResourceServerAutoConfiguration.class,
        SecurityAutoConfiguration.class, UserDetailsServiceAutoConfiguration.class })
@ConditionalOnClass(name = "org.springframework.security.oauth2.jwt.JwtDecoder")
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "toolbox.auth", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ToolboxAuthProperties.class)
public class ToolboxAuthAutoConfiguration {

    /** Seam 2（07 §5.3）：换校验行为。构造期 R5 fail-fast；JWKS 懒加载（07 §5.4）。 */
    @Bean
    @ConditionalOnMissingBean
    public JwtDecoder toolboxAuthJwtDecoder(ToolboxAuthProperties props) {
        var kc = props.keycloak();
        var jwt = props.jwt();
        return KeycloakJwtDecoderFactory.create(kc.serverUrl(), kc.realm(), kc.issuerUri(),
                kc.requiredAudience(), jwt.clockSkew(), jwt.jwsAlgorithms());
    }

    /** Seam 3（07 §5.3）：换映射行为（自类型条件）。 */
    @Bean
    @ConditionalOnMissingBean
    public KeycloakJwtAuthenticationConverter toolboxAuthJwtAuthenticationConverter(ToolboxAuthProperties props) {
        var jwt = props.jwt();
        return new KeycloakJwtAuthenticationConverter(
                jwt.mapRealmRoles(), jwt.clientRoles(), jwt.mapScopes(), jwt.principalClaim());
    }

    /**
     * 模块 i18n（00 §4.1 约定）：注册模块自有 MessageSource 参与 facility
     * {@code AggregatedMessageSource} 聚合（同 compare/storage 样板）；键前缀
     * {@code toolbox.auth.} 防聚合池污染。
     */
    @Bean("toolboxAuthMessageSource")
    @ConditionalOnMissingBean(name = "toolboxAuthMessageSource")
    public ResourceBundleMessageSource toolboxAuthMessageSource() {
        ResourceBundleMessageSource messageSource = new ResourceBundleMessageSource();
        messageSource.setBasename("i18n/toolbox-auth-messages");
        messageSource.setDefaultEncoding("UTF-8");
        messageSource.setUseCodeAsDefaultMessage(false);
        messageSource.setFallbackToSystemLocale(false);
        return messageSource;
    }

    /**
     * 默认安全链（Seam 1，主 Seam；07 §5.3 默认链形态）：无状态、CSRF 关、CORS 委托
     * （有 CorsConfigurationSource bean 用之，否则回落 MVC 配置——facility CORS 自动生效）、
     * fail-closed（permit-paths 之外全鉴权，不内置默认放行）、401/403 双挂保证形态处处一致、
     * bridge 开启时挂 {@link SessionUserBridgeFilter}（认证后、授权前）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.web.SecurityFilterChain")
    @ConditionalOnProperty(prefix = "toolbox.auth.security-chain", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    static class SecurityChainConfiguration {

        @Bean
        @ConditionalOnMissingBean(SecurityFilterChain.class)
        SecurityFilterChain toolboxAuthSecurityFilterChain(HttpSecurity http, ToolboxAuthProperties props,
                                                           JwtDecoder decoder,
                                                           KeycloakJwtAuthenticationConverter converter)
                throws Exception {
            AuthEntryPoint entryPoint = new AuthEntryPoint();
            AuthAccessDeniedHandler deniedHandler = new AuthAccessDeniedHandler();
            http.csrf(AbstractHttpConfigurer::disable)
                    .cors(Customizer.withDefaults())
                    .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .authorizeHttpRequests(reg -> {
                        var permitPaths = props.securityChain().permitPaths();
                        if (!permitPaths.isEmpty()) {
                            reg.requestMatchers(permitPaths.toArray(String[]::new)).permitAll();
                        }
                        reg.anyRequest().authenticated();
                    })
                    .oauth2ResourceServer(rs -> rs
                            .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
                            .authenticationEntryPoint(entryPoint)
                            .accessDeniedHandler(deniedHandler))
                    .exceptionHandling(e -> e
                            .authenticationEntryPoint(entryPoint)
                            .accessDeniedHandler(deniedHandler));
            if (props.bridge().sessionUser().enabled()) {
                http.addFilterBefore(new SessionUserBridgeFilter(), AuthorizationFilter.class);
            }
            return http.build();
        }
    }

    /**
     * 方法级安全（07 §5.3）：默认开，@PreAuthorize 即用。消费方已自行 @EnableMethodSecurity
     * 时重复声明幂等（同名基础设施 bean 覆盖注册）；要完全自持则设
     * {@code toolbox.auth.method-security.enabled=false}。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "toolbox.auth.method-security", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    @EnableMethodSecurity
    static class MethodSecurityConfiguration {
    }
}
```

`org.springframework.boot.autoconfigure.AutoConfiguration.imports`（单行）：

```
cn.code91.toolbox.auth.autoconfigure.ToolboxAuthAutoConfiguration
```

- [ ] **Step 3: 写装配矩阵失败测试**

```java
package cn.code91.toolbox.auth.autoconfigure;

import cn.code91.toolbox.auth.jwt.KeycloakJwtAuthenticationConverter;
import cn.code91.toolbox.auth.web.SessionUserBridgeFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.security.SecurityAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L1-L4 装配矩阵（07 §9 装配行）。WebApplicationContextRunner 叠加 SecurityAutoConfiguration
 * 以获得 HttpSecurity 原型 bean（Boot 官方装配测试同法）。
 */
@DisplayName("ToolboxAuthAutoConfiguration 装配矩阵")
class ToolboxAuthAutoConfigurationTest {

    private static final String[] MINIMAL = {
            "toolbox.auth.keycloak.server-url=https://kc.example.com",
            "toolbox.auth.keycloak.realm=demo" };

    private final WebApplicationContextRunner webRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ToolboxAuthAutoConfiguration.class, SecurityAutoConfiguration.class));

    @Test
    void assemblesWithMinimalConfig() {
        webRunner.withPropertyValues(MINIMAL).run(context -> {
            assertThat(context).as("最小配置全量装配").hasNotFailed();
            assertThat(context).hasSingleBean(JwtDecoder.class);
            assertThat(context).hasSingleBean(KeycloakJwtAuthenticationConverter.class);
            assertThat(context).hasSingleBean(SecurityFilterChain.class);
            assertThat(context).hasBean("toolboxAuthMessageSource");
        });
    }

    @Test
    void missingRequiredConfigFailsStartupWithGuidance() {
        webRunner.run(context -> {
            assertThat(context).as("R5：缺必填启动失败（ADR-0018 有意偏离，07 §5.4）").hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(rootMessage(context.getStartupFailure()))
                    .as("失败消息带配置引导")
                    .contains("toolbox.auth.keycloak.server-url")
                    .contains("toolbox.auth.enabled=false");
        });
    }

    @Test
    void disabledSwitchBacksOffEntirely() {
        webRunner.withPropertyValues("toolbox.auth.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).as("L2 关停零装配").doesNotHaveBean(JwtDecoder.class);
            assertThat(context).doesNotHaveBean(KeycloakJwtAuthenticationConverter.class);
        });
    }

    @Test
    void missingSecurityClassesBackOff() {
        webRunner.withPropertyValues(MINIMAL)
                .withClassLoader(new FilteredClassLoader(JwtDecoder.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).as("L1：缺 oauth2-jose 类零装配")
                            .doesNotHaveBean(KeycloakJwtAuthenticationConverter.class);
                });
    }

    @Test
    void nonServletEnvironmentBacksOff() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolboxAuthAutoConfiguration.class))
                .withPropertyValues(MINIMAL)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).as("非 Servlet 环境零装配")
                            .doesNotHaveBean(KeycloakJwtAuthenticationConverter.class);
                });
    }

    @Test
    void userChainWinsAndModuleChainBacksOff() {
        webRunner.withPropertyValues(MINIMAL)
                .withUserConfiguration(UserChainConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).as("Seam 1：用户整链替换").hasSingleBean(SecurityFilterChain.class);
                    assertThat(context.getBean(SecurityFilterChain.class))
                            .isSameAs(context.getBean(UserChainConfig.class).chain);
                    assertThat(context).as("decoder/converter 仍在，供自建链复用（07 §5.3 指引）")
                            .hasSingleBean(JwtDecoder.class);
                });
    }

    @Test
    void userDecoderAndConverterWinOverModuleDefaults() {
        webRunner.withPropertyValues(MINIMAL)
                .withUserConfiguration(UserSeamBeansConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(JwtDecoder.class))
                            .as("Seam 2：用户 decoder 退让模块默认件")
                            .isSameAs(context.getBean(UserSeamBeansConfig.class).decoder);
                    assertThat(context.getBean(KeycloakJwtAuthenticationConverter.class))
                            .as("Seam 3：用户 converter 退让模块默认件")
                            .isSameAs(context.getBean(UserSeamBeansConfig.class).converter);
                });
    }

    @Test
    void chainSubSwitchLeavesOnlyDecoderAndConverter() {
        webRunner.withPropertyValues(MINIMAL)
                .withPropertyValues("toolbox.auth.security-chain.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(SecurityFilterChain.class);
                    assertThat(context).hasSingleBean(JwtDecoder.class);
                    assertThat(context).hasSingleBean(KeycloakJwtAuthenticationConverter.class);
                });
    }

    @Test
    void bridgeSwitchAddsFilterToChain() {
        webRunner.withPropertyValues(MINIMAL).run(context -> {
            var filters = ((DefaultSecurityFilterChain) context.getBean(SecurityFilterChain.class)).getFilters();
            assertThat(filters).as("R3 默认关：链中无桥接 filter")
                    .noneMatch(f -> f instanceof SessionUserBridgeFilter);
        });
        webRunner.withPropertyValues(MINIMAL)
                .withPropertyValues("toolbox.auth.bridge.session-user.enabled=true")
                .run(context -> {
                    var filters = ((DefaultSecurityFilterChain) context.getBean(SecurityFilterChain.class)).getFilters();
                    assertThat(filters).as("开关开启后桥接 filter 入链")
                            .anyMatch(f -> f instanceof SessionUserBridgeFilter);
                });
    }

    @Test
    void methodSecuritySwitchControlsNestedConfig() {
        webRunner.withPropertyValues(MINIMAL).run(context ->
                assertThat(context).hasSingleBean(ToolboxAuthAutoConfiguration.MethodSecurityConfiguration.class));
        webRunner.withPropertyValues(MINIMAL)
                .withPropertyValues("toolbox.auth.method-security.enabled=false")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(ToolboxAuthAutoConfiguration.MethodSecurityConfiguration.class));
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return String.valueOf(cur.getMessage());
    }

    @Configuration(proxyBeanMethods = false)
    static class UserChainConfig {
        SecurityFilterChain chain;

        @Bean
        SecurityFilterChain customChain(HttpSecurity http) throws Exception {
            chain = http.securityMatcher("/custom/**").build();
            return chain;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class UserSeamBeansConfig {
        JwtDecoder decoder = token -> { throw new UnsupportedOperationException("test stub"); };
        KeycloakJwtAuthenticationConverter converter =
                new KeycloakJwtAuthenticationConverter(false, java.util.List.of(), false, "sub");

        @Bean
        JwtDecoder userDecoder() {
            return decoder;
        }

        @Bean
        KeycloakJwtAuthenticationConverter userConverter() {
            return converter;
        }
    }
}
```

注意：`MethodSecurityConfiguration` 需要包可见性供测试引用（nested static class 默认 package-private 即可，测试同包不同子包——**测试类放 `autoconfigure` 同包**，如上路径已是）。

- [ ] **Step 4: 跑装配矩阵，逐条修复**

```powershell
mvn -q -pl auth-enhancement test "-Dtest=ToolboxAuthAutoConfigurationTest"
```

预期首轮可能有环境性失败（如 HttpSecurity 装配还需 `spring-boot-starter-web` 的 test 依赖提供 Servlet API mock——已在 Task 1 pom 就位）。逐条修至全绿；**若 `userChainWinsAndModuleChainBacksOff` 因用户链 bean 注册顺序失败**，将用户配置改为 `@Bean @Order(0)` 或直接断言存在两个 chain 中用户 chain 生效——按实际行为修测试断言，不改生产代码语义（`@ConditionalOnMissingBean(SecurityFilterChain.class)` 对用户 bean 的退让是 Boot 标准语义，装配顺序上用户配置先于自动装配注册）。

- [ ] **Step 5: 写 ArchitectureTest**

```java
package cn.code91.toolbox.auth.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import com.tngtech.archunit.library.dependencies.SliceAssignment;
import com.tngtech.archunit.library.dependencies.SliceIdentifier;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * 全局约束固定三规则（00 §5.4；第三条按 07 §4.1 调整）：包无环；autoconfigure 不被主包
 * 依赖；core 依赖白名单——允许 spring-security-core（SecurityContextHolder）与
 * oauth2-jose（Jwt 值类型），禁 servlet / security-web / security-config /
 * oauth2-resource-server（AuthContext 判 principal instanceof Jwt，不触
 * JwtAuthenticationToken，依赖面钉死）。
 */
class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("cn.code91.toolbox.auth");

    @Test
    void topLevelSlicesShouldBeFreeOfCycles() {
        slices().assignedFrom(new TopLevelPackageSliceAssignment()).should().beFreeOfCycles().check(CLASSES);
    }

    @Test
    void autoconfigurePackageShouldNotBeDependedOnByOtherPackages() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideOutsideOfPackage("cn.code91.toolbox.auth.autoconfigure..")
                .should().dependOnClassesThat().resideInAPackage("cn.code91.toolbox.auth.autoconfigure..");
        rule.check(CLASSES);
    }

    @Test
    void coreShouldNotDependOnServletOrSecurityWebTypes() {
        ArchRule rule = ArchRuleDefinition.noClasses()
                .that().resideInAPackage("cn.code91.toolbox.auth.core..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "jakarta.servlet..",
                        "org.springframework.security.web..",
                        "org.springframework.security.config..",
                        "org.springframework.security.oauth2.server.resource..");
        rule.check(CLASSES);
    }

    /** 顶层包 → slice（core/jwt/web/autoconfigure），同 llm 模板。 */
    private static final class TopLevelPackageSliceAssignment implements SliceAssignment {

        @Override
        public SliceIdentifier getIdentifierOf(JavaClass javaClass) {
            String packageName = javaClass.getPackageName();
            String prefix = "cn.code91.toolbox.auth.";
            if (!packageName.startsWith(prefix)) {
                return SliceIdentifier.ignore();
            }
            String rest = packageName.substring(prefix.length());
            int dot = rest.indexOf('.');
            return SliceIdentifier.of(dot < 0 ? rest : rest.substring(0, dot));
        }

        @Override
        public String getDescription() {
            return "cn.code91.toolbox.auth 顶层包切片";
        }
    }
}
```

- [ ] **Step 6: 全模块跑测 + 提交**

```powershell
mvn -q -pl auth-enhancement test
```

预期：Task 1-5 全部测试绿。

```powershell
git add auth-enhancement/src
git commit -m "auth-enhancement: 自动装配（三级 Seam + R5 fail-fast + Boot 退让链）与装配矩阵、ArchUnit"
```

---

### Task 6: WireMock 全链集成矩阵（无 Docker 主矩阵）

**Files:**
- Create: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/integration/TestTokens.java`
- Create: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/integration/AuthTestApp.java`
- Create: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/integration/JwksFullChainTest.java`
- Create: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/integration/JwksUnavailableTest.java`
- Create: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/web/SecurityExceptionAdvice.java`（实施实测补件，见 Step 4b）
- Modify: `auth-enhancement/src/main/java/cn/code91/toolbox/auth/autoconfigure/ToolboxAuthAutoConfiguration.java`（注册 advice，见 Step 4b）
- Modify: `auth-enhancement/pom.xml`（wiremock-jetty12 test 件，Task 1 节已修正记录）

**Interfaces:**
- Consumes: Task 5 装配全量；facility `SessionUserHolder`。
- Produces: `TestTokens`（静态 RSA 密钥对 + `jwksJson()` + `mint(...)` 铸 token 工具，Task 7 不用——真 KC 签发）；`AuthTestApp`（`@SpringBootApplication` 测试宿主 + 受保护端点，Task 7 复用）。

- [ ] **Step 1: 铸 token 工具**

```java
package cn.code91.toolbox.auth.integration;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 测试铸 token（07 §9 无 Docker 主矩阵）：进程内 RSA 密钥对，公钥经 WireMock 假 JWKS
 * 暴露，私钥签发可控 claim 的 JWT——坏签名用第二把独立密钥。
 */
final class TestTokens {

    static final RSAKey KEY;
    static final RSAKey ROGUE_KEY;   // 坏签名场景：不在 JWKS 中的密钥

    static {
        try {
            KEY = new RSAKeyGenerator(2048).keyID("it-key").generate();
            ROGUE_KEY = new RSAKeyGenerator(2048).keyID("rogue-key").generate();
        } catch (Exception e) {
            throw new IllegalStateException("测试密钥生成失败", e);
        }
    }

    private TestTokens() {
    }

    /** {@return 仅含公钥的 JWKS JSON}（WireMock 响应体） */
    static String jwksJson() {
        return new JWKSet(KEY.toPublicJWK()).toString();
    }

    /** 铸标准 KC 形态 token；customizer 可改任意 claim（过期/错 issuer/错 aud 等场景） */
    static String mint(String issuer, Consumer<JWTClaimsSet.Builder> customizer) {
        return mintWith(KEY, issuer, customizer);
    }

    /** 用不在 JWKS 的密钥签发（坏签名场景） */
    static String mintRogue(String issuer) {
        return mintWith(ROGUE_KEY, issuer, b -> { });
    }

    private static String mintWith(RSAKey key, String issuer, Consumer<JWTClaimsSet.Builder> customizer) {
        try {
            JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                    .subject("2f0c3f9a-0000-0000-0000-000000000001")
                    .issuer(issuer)
                    .audience(List.of("toolbox-api"))
                    .issueTime(Date.from(Instant.now().minusSeconds(5)))
                    .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                    .claim("preferred_username", "alice")
                    .claim("email", "alice@example.com")
                    .claim("realm_access", Map.of("roles", List.of("order-admin")))
                    .claim("resource_access", Map.of("toolbox-api", Map.of("roles", List.of("doc-reader"))))
                    .claim("scope", "openid profile");
            customizer.accept(builder);
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    builder.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("测试 token 签发失败", e);
        }
    }
}
```

- [ ] **Step 2: 测试宿主应用**

```java
package cn.code91.toolbox.auth.integration;

import cn.code91.facility.web.session.SessionUserHolder;
import cn.code91.toolbox.auth.core.AuthContext;
import cn.code91.toolbox.auth.core.CurrentUser;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 全链测试宿主（07 §9）：受保护端点回显 AuthContext 视图；@PreAuthorize 端点验证
 * method-security 默认开；/public 验证 permit-paths；/bridge 验证 SessionUserHolder 桥接。
 */
@SpringBootApplication
public class AuthTestApp {

    @RestController
    static class ProbeController {

        @GetMapping("/api/me")
        Map<String, Object> me() {
            CurrentUser user = AuthContext.current().orElseThrow();
            return Map.of(
                    "sub", user.sub(),
                    "username", user.username(),
                    "realmRoles", user.realmRoles(),
                    "hasOrderAdmin", AuthContext.hasRole("order-admin"),
                    "hasDocReader", AuthContext.hasClientRole("toolbox-api", "doc-reader"));
        }

        @GetMapping("/api/admin-only")
        @PreAuthorize("hasRole('order-admin')")
        Map<String, Object> adminOnly() {
            return Map.of("ok", true);
        }

        @GetMapping("/api/super-only")
        @PreAuthorize("hasRole('super-admin')")
        Map<String, Object> superOnly() {
            return Map.of("ok", true);
        }

        @GetMapping("/public/ping")
        Map<String, Object> ping() {
            return Map.of("pong", true);
        }

        @GetMapping("/api/bridge")
        Map<String, Object> bridge() {
            return Map.of("bridged", SessionUserHolder.getUser(CurrentUser.class).isPresent());
        }
    }
}
```

- [ ] **Step 3: 写全链矩阵测试**

```java
package cn.code91.toolbox.auth.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Date;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * WireMock 假 JWKS 全链矩阵（07 §9）：200/401（过期/坏签/错 issuer/错 aud/无 token）/403
 * （缺角色）/permit-paths/桥接/方法级安全。WireMock 用静态单例（同 database PG 容器
 * 单例模式），@DynamicPropertySource 注入派生 server-url。
 */
@SpringBootTest(classes = AuthTestApp.class)
@AutoConfigureMockMvc
@DisplayName("JWKS 全链矩阵")
class JwksFullChainTest {

    static final WireMockServer WIREMOCK = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        WIREMOCK.start();
        // WireMock 的 get/aResponse 与 MockMvcRequestBuilders.get 静态导入冲突，此处用类名限定
        WIREMOCK.stubFor(WireMock.get(WireMock.urlEqualTo("/realms/it/protocol/openid-connect/certs"))
                .willReturn(WireMock.aResponse().withHeader("Content-Type", "application/json")
                        .withBody(TestTokens.jwksJson())));
    }

    @DynamicPropertySource
    static void authProps(DynamicPropertyRegistry registry) {
        registry.add("toolbox.auth.keycloak.server-url", WIREMOCK::baseUrl);
        registry.add("toolbox.auth.keycloak.realm", () -> "it");
        registry.add("toolbox.auth.keycloak.required-audience", () -> "toolbox-api");
        registry.add("toolbox.auth.jwt.client-roles", () -> "toolbox-api");
        registry.add("toolbox.auth.security-chain.permit-paths", () -> "/public/**");
        registry.add("toolbox.auth.bridge.session-user.enabled", () -> "true");
    }

    @Autowired
    MockMvc mockMvc;

    private String issuer() {
        return WIREMOCK.baseUrl() + "/realms/it";
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    void validTokenReturns200WithMappedIdentity() throws Exception {
        mockMvc.perform(get("/api/me").header("Authorization", bearer(TestTokens.mint(issuer(), b -> { }))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.realmRoles[0]").value("order-admin"))
                .andExpect(jsonPath("$.hasDocReader").value(true));
    }

    @Test
    void missingTokenReturns401BaseResponseShape() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void expiredTokenReturns401InvalidToken() throws Exception {
        String expired = TestTokens.mint(issuer(), b -> b
                .issueTime(Date.from(Instant.now().minusSeconds(7200)))
                .expirationTime(Date.from(Instant.now().minusSeconds(3600))));
        mockMvc.perform(get("/api/me").header("Authorization", bearer(expired)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"invalid_token\""))
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.description").value("invalid_token"));
    }

    @Test
    void rogueSignatureReturns401() throws Exception {
        mockMvc.perform(get("/api/me").header("Authorization", bearer(TestTokens.mintRogue(issuer()))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void wrongIssuerReturns401() throws Exception {
        String wrongIss = TestTokens.mint("https://evil.example/realms/it", b -> { });
        mockMvc.perform(get("/api/me").header("Authorization", bearer(wrongIss)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongAudienceReturns401() throws Exception {
        String wrongAud = TestTokens.mint(issuer(), b -> b.audience("other-api"));
        mockMvc.perform(get("/api/me").header("Authorization", bearer(wrongAud)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void preAuthorizeAllowsAndDenies() throws Exception {
        String token = TestTokens.mint(issuer(), b -> { });
        mockMvc.perform(get("/api/admin-only").header("Authorization", bearer(token)))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/super-only").header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(header().string("WWW-Authenticate", "Bearer error=\"insufficient_scope\""))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.description").value("insufficient_scope"));
    }

    @Test
    void permitPathsBypassAuthentication() throws Exception {
        mockMvc.perform(get("/public/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pong").value(true));
    }

    @Test
    void bridgePopulatesSessionUserHolderWithinRequest() throws Exception {
        mockMvc.perform(get("/api/bridge").header("Authorization", bearer(TestTokens.mint(issuer(), b -> { }))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bridged").value(true));
    }
}
```

- [ ] **Step 4: 写"JWKS 不可达 ⇒ 401 非 500"独立上下文测试（spec §9 明列）**

创建 `auth-enhancement/src/test/java/cn/code91/toolbox/auth/integration/JwksUnavailableTest.java`：

```java
package cn.code91.toolbox.auth.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * KC 不可达语义钉（07 §5.4）：服务照常启动（构建期零网络），bearer 请求得 401 而非 500——
 * IdP 故障不得放大为服务端错误。9 端口（discard）loopback 连接必被快速拒绝。
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
        String token = TestTokens.mint("http://127.0.0.1:9/realms/it", b -> { });
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }
}
```

若实测得 500：说明 JWKS 取用异常未被映射为 `AuthenticationException`（Spring 的 `JwtAuthenticationProvider` 通常把 `JwtException` 转 `InvalidBearerTokenException`，应得 401）——此时按 07 §5.4 语义修生产码（decoder 外包一层把取键失败统一转 `JwtException`），**不得**改测试断言迁就 500。

- [ ] **Step 4b: SecurityExceptionAdvice（Task 6 全链矩阵实证补件，裁定方向 A）**

矩阵实证发现：`@PreAuthorize` 拒绝抛出的 `AccessDeniedException` 发生在 controller 调用内，走 MVC 异常解析而非链上 ExceptionTranslationFilter——facility 的全局兜底 `@ExceptionHandler(Exception.class)`（无 @Order = LOWEST_PRECEDENCE）会把它吃成 200/500 响应体，撕破 07 §4.3"401/403 形态处处一致"承诺。补件：模块自带高优先级 advice，仅接管两类安全异常并委托既有 handler，其余异常仍归 facility；优先级留 1000 空间供消费方自有 advice 覆盖（L4 精神）；不挂 security-chain 子开关（自建链消费方同样受益，它是设计承诺组成部分而非新特性）。

创建 `SecurityExceptionAdvice.java`：

```java
package cn.code91.toolbox.auth.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;

/**
 * 方法级安全异常的 MVC 层出口（Task 6 全链矩阵实证补件，07 §4.3"形态处处一致"承诺的组成部分）。
 * {@code @PreAuthorize} 拒绝抛出的 {@link AccessDeniedException} 发生在 controller 调用内，
 * 走 MVC 异常解析而非链上 ExceptionTranslationFilter——若无本 advice，facility 的全局兜底
 * {@code @ExceptionHandler(Exception.class)}（默认 LOWEST_PRECEDENCE）会把它吃成 200/500。
 * 本 advice 以高优先级抢先按 07 §4.3 形态写出 401/403，其余异常不碰（仍归 facility）。
 * 注：web 包"实现类不引 spring-web"约束（07 §4.3）针对 EntryPoint/Filter；本类是 MVC 组件，
 * 必然依赖 spring-web 注解（optional 编译面依赖已由 Task 5 裁定引入）。
 */
@RestControllerAdvice
@Order(SecurityExceptionAdvice.ORDER)
public class SecurityExceptionAdvice {

    /** 高优先级但可被消费方超越（Ordered.HIGHEST_PRECEDENCE + 1000，L4 精神） */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 1000;

    private final AuthEntryPoint entryPoint = new AuthEntryPoint();
    private final AuthAccessDeniedHandler deniedHandler = new AuthAccessDeniedHandler();

    /** 方法级授权拒绝 → 403（与链上 AuthorizationFilter 路径同形态） */
    @ExceptionHandler(AccessDeniedException.class)
    public void handleAccessDenied(HttpServletRequest request, HttpServletResponse response,
                                   AccessDeniedException ex) throws IOException {
        deniedHandler.handle(request, response, ex);
    }

    /** MVC 层认证异常（罕见：permit 路径上匿名触达受保护方法等） → 401 */
    @ExceptionHandler(AuthenticationException.class)
    public void handleAuthentication(HttpServletRequest request, HttpServletResponse response,
                                     AuthenticationException ex) throws IOException {
        entryPoint.commence(request, response, ex);
    }
}
```

`ToolboxAuthAutoConfiguration` 追加嵌套配置（与 SecurityChainConfiguration 平级）：

```java
    /**
     * 方法级安全异常出口（Task 6 实证补件，07 §4.3）：@PreAuthorize 拒绝走 MVC 异常解析，
     * 需 advice 抢在 facility 全局兜底前写出 403；非链组件，自建链（Seam 1）消费方同样受益，
     * 故不挂 security-chain 子开关，仅 L4（自声明同类型 bean）可覆盖。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestControllerAdvice")
    static class MvcSecurityExceptionConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public SecurityExceptionAdvice toolboxAuthSecurityExceptionAdvice() {
            return new SecurityExceptionAdvice();
        }
    }
```

装配矩阵补一例（`ToolboxAuthAutoConfigurationTest`）：

```java
    @Test
    void securityExceptionAdviceAssembledAndOverridable() {
        webRunner.withPropertyValues(MINIMAL).run(context ->
                assertThat(context).as("方法级安全异常出口默认装配（07 §4.3 补件）")
                        .hasSingleBean(SecurityExceptionAdvice.class));
        webRunner.withPropertyValues(MINIMAL)
                .withUserConfiguration(UserAdviceConfig.class)
                .run(context -> assertThat(context.getBean(SecurityExceptionAdvice.class))
                        .as("L4：用户自有 advice 退让模块默认件")
                        .isSameAs(context.getBean(UserAdviceConfig.class).advice));
    }

    @Configuration(proxyBeanMethods = false)
    static class UserAdviceConfig {
        SecurityExceptionAdvice advice = new SecurityExceptionAdvice();

        @Bean
        SecurityExceptionAdvice userAdvice() {
            return advice;
        }
    }
```

既有矩阵用例 `preAuthorizeAllowsAndDenies`（403 + insufficient_scope + BaseResponse shape，且运行于 facility 兜底 advice 同在的上下文）即为本补件的端到端回归钉，无需另写。

- [ ] **Step 5: 跑全链矩阵，逐条修复**

```powershell
mvn -q -pl auth-enhancement test "-Dtest=JwksFullChainTest,JwksUnavailableTest"
```

已知可能的修点：
- `@DynamicPropertySource` 的 list 型属性（`client-roles`/`permit-paths`）绑定：若逗号分隔单值写法不生效，改 `toolbox.auth.jwt.client-roles[0]=toolbox-api` 索引式键。
- WireMock 起在静态块（house 单例容器模式）保证先于 Spring 上下文；若仍有时序问题查 `@DirtiesContext` 泄漏而非改动模式。
- `JwtTimestampValidator` 有 60s 默认偏移：过期用例的 exp 设为 1 小时前（已如此），不会被偏移救活。

- [ ] **Step 6: 提交**

```powershell
git add auth-enhancement/src
git commit -m "auth-enhancement: WireMock 假 JWKS 全链矩阵（200/401×5/403/放行/桥接）"
```

---

### Task 7: 真 Keycloak IT + README/USAGE + 收尾 verify

**Files:**
- Create: `auth-enhancement/src/test/resources/keycloak/it-realm.json`
- Create: `auth-enhancement/src/test/java/cn/code91/toolbox/auth/integration/KeycloakRealChainIT.java`
- Create: `auth-enhancement/README.md`
- Create: `auth-enhancement/USAGE.md`
- Modify: `docs/design/07-auth-enhancement.md`（仅头部状态行）

**Interfaces:**
- Consumes: `AuthTestApp`（Task 6）、装配全量（Task 5）。
- Produces: 无后续任务。

- [ ] **Step 1: realm 导入夹具**

`src/test/resources/keycloak/it-realm.json`：

```json
{
  "realm": "it-realm",
  "enabled": true,
  "roles": {
    "realm": [{ "name": "order-admin" }],
    "client": { "toolbox-api": [{ "name": "doc-reader" }] }
  },
  "clients": [
    {
      "clientId": "toolbox-api",
      "enabled": true,
      "publicClient": false,
      "standardFlowEnabled": false,
      "directAccessGrantsEnabled": false,
      "serviceAccountsEnabled": false,
      "secret": "toolbox-api-secret"
    },
    {
      "clientId": "login-client",
      "enabled": true,
      "publicClient": true,
      "standardFlowEnabled": false,
      "directAccessGrantsEnabled": true,
      "protocolMappers": [
        {
          "name": "toolbox-api-audience",
          "protocol": "openid-connect",
          "protocolMapper": "oidc-audience-mapper",
          "consentRequired": false,
          "config": {
            "included.client.audience": "toolbox-api",
            "access.token.claim": "true",
            "id.token.claim": "false"
          }
        }
      ]
    }
  ],
  "users": [
    {
      "username": "alice",
      "enabled": true,
      "email": "alice@example.com",
      "firstName": "Alice",
      "lastName": "Doe",
      "credentials": [{ "type": "password", "value": "alice-pw", "temporary": false }],
      "realmRoles": ["order-admin"],
      "clientRoles": { "toolbox-api": ["doc-reader"] }
    }
  ]
}
```

- [ ] **Step 2: 写真 KC IT**

```java
package cn.code91.toolbox.auth.integration;

import cn.code91.facility.json.JsonUtil;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真 Keycloak 端到端（07 §9 Docker 行）：password grant 取真 token，验证真实 token 形态下的
 * 角色映射/principal/audience mapper——防"自签 token 与真 KC 漂移"。单例容器 +
 * disabledWithoutDocker（同 database PG 模式）。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = AuthTestApp.class)
@AutoConfigureMockMvc
@DisplayName("真 Keycloak 端到端（IT）")
class KeycloakRealChainIT {

    static final KeycloakContainer KEYCLOAK = new KeycloakContainer()
            .withRealmImportFile("keycloak/it-realm.json");

    static {
        KEYCLOAK.start();
    }

    @DynamicPropertySource
    static void authProps(DynamicPropertyRegistry registry) {
        registry.add("toolbox.auth.keycloak.server-url", KeycloakRealChainIT::serverUrl);
        registry.add("toolbox.auth.keycloak.realm", () -> "it-realm");
        registry.add("toolbox.auth.keycloak.required-audience", () -> "toolbox-api");
        registry.add("toolbox.auth.jwt.client-roles", () -> "toolbox-api");
    }

    private static String serverUrl() {
        String url = KEYCLOAK.getAuthServerUrl();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Autowired
    MockMvc mockMvc;

    @Test
    void realTokenPassesFullChainWithMappedRoles() throws Exception {
        String token = passwordGrantToken();
        mockMvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.hasOrderAdmin").value(true))
                .andExpect(jsonPath("$.hasDocReader").value(true));
        mockMvc.perform(get("/api/admin-only").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void unauthenticatedStillRejected() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
    }

    /** password grant（login-client 开 directAccessGrants；audience mapper 补 aud=toolbox-api） */
    private static String passwordGrantToken() throws Exception {
        String form = "grant_type=password&client_id=login-client&username=alice&password=alice-pw";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(serverUrl() + "/realms/it-realm/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString());
        Map<?, ?> body = JsonUtil.deserialize(response.body(), Map.class)
                .expect("token 端点响应必须是 JSON");
        return String.valueOf(body.get("access_token"));
    }
}
```

> 同 Task 4 注：`Result.expect(...)` 方法名以 facility 实际 API 为准（实现前先看 `cn.code91.facility.result.Result` 公开方法，取"失败即抛"的解包方法）。刻意用 `Map.class` 而非 jackson `TypeReference`——后者会引入 jackson-core 直接使用，触发依赖账目连锁（test 作用域声明还有 nearest-wins 遮蔽风险，P2 storage spring-beans 实测教训）。

- [ ] **Step 3: 跑 IT（需 Docker）**

```powershell
mvn -q -pl auth-enhancement test "-Dtest=KeycloakRealChainIT"
```

预期：Docker 在场 2 用例绿（首跑拉镜像耗时数分钟）；无 Docker 则整类跳过。

- [ ] **Step 4: README + USAGE**

`README.md`（对齐兄弟模块速览结构——定位、快速开始、配置速查表、Seam 指引；内容取 07 §1/§6/§8，此处不重复全文，要点必须含：三步快速开始（引依赖+starter / server-url+realm / @PreAuthorize 即用）、全配置键表含默认值、三级 Seam 表、"缺配置启动失败"与 `enabled=false` 出口、桥接开关）。

`USAGE.md`（07 §8 交付清单，必须含）：
1. KC 侧准备：建 realm/client 步骤；**audience mapper 完整操作路径**（Clients → 你的登录 client → Client scopes → dedicated scope → Add mapper → By configuration → Audience → Included Client Audience 选 API client → Add to access token 开）；角色建模建议（realm 角色 vs client 角色选用）。
2. `permit-paths` 与 actuator 显式放行示例。
3. 自建 chain 菜谱：`security-chain.enabled=false` + 注入 decoder/converter + 复用 `SessionUserBridgeFilter` 的完整代码块。
4. 内外网地址不一致：`issuer-uri` 覆写示例。
5. 真实 KC 手动冒烟清单（不进 CI）：换真实 server-url/realm、验证 200/401/403 三态、角色映射抽查。

- [ ] **Step 5: 设计文档状态行更新**

`docs/design/07-auth-enhancement.md` 首行下方状态行：

```
> 状态：P1 已实施（feat/auth-enhancement-p1）｜原设计稿 v0.1 裁定 R1-R5｜日期：2026-07-09
```

（只改这一行；文档其余内容与实现一致性由终审核对。）

- [ ] **Step 6: 全模块 verify（jacoco + analyze + ArchUnit 门禁）**

```powershell
mvn -pl auth-enhancement clean verify
```

预期全绿：surefire 全过、jacoco ≥ 80/80/70、dependency:analyze 零告警、ArchUnit 过。若 jacoco 分支不足，优先补 Properties 紧凑构造器 null 分支与 `KeycloakClaims` 畸形结构分支的用例（而非降门禁——门禁在父 pom，不可动）。

- [ ] **Step 7: 全仓回归 + 提交**

```powershell
mvn clean verify
```

预期：全仓 629 + 新增测试全绿（其他模块零改动，纯回归确认）。

```powershell
git add auth-enhancement docs/design/07-auth-enhancement.md
git commit -m "auth-enhancement: 真 Keycloak IT、README/USAGE 与设计文档状态更新"
```

**注意**：不要把 `README.md`（根）、`docs/design/00-overview.md`、`docs/design/06-docs-enhancement.md` 加入任何提交（并行会话在途工作）。分支合并回 master 由用户拍板（同 M0-M4 惯例，走终审）。
