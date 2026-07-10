# auth-enhancement 设计文档

> 状态：设计稿 v0.1（裁定 R1-R5 已拍板，未编码）｜日期：2026-07-09｜配置前缀 `toolbox.auth.*`｜基础包 `cn.code91.toolbox.auth`
> 调研基线：Keycloak 26.7.0（2026-07 发布，稳定推荐 26.6.3）、Spring Boot 3.5.10（Spring Security 6.5.x）、dasniko/testcontainers-keycloak 4.1.1
> 参考：beacon-support 无 auth 模块（本设计为全新调研）；装配范式对位本仓库 00-overview §3

## 1. 模块定位

**Keycloak 资源服务器鉴权件**：消费方引依赖 + 配 `server-url`/`realm` 两项，即获得 JWT 本地校验、Keycloak 私有角色结构映射（`realm_access`/`resource_access` → `GrantedAuthority`）、开箱即用的无状态安全链、facility `BaseResponse` 风格的 401/403 JSON 响应与方法级安全（`@PreAuthorize`）。

- **解决什么**：Spring Security 只认标准 OAuth2 claim，Keycloak 的角色放在私有结构里，每个项目都要手写同一个 Converter；外加三个著名坑（见 §2.2）。这些胶水 + facility 错误风格对齐，构成一个"接口窄、承载宽"的深模块。
- **不做什么**：交互式登录（authorization_code / OIDC Login）、Keycloak Admin Client、S2S 取令牌客户端（P2，裁定 R1）、opaque token introspection、WebFlux（facility 是 servlet 世界）。排除理由见 §2.3。
- **Keycloak 依赖形态**：**零 Keycloak 运行时依赖**——核心只用 Spring Security 标准件，任何 OIDC 兼容 IdP 理论可用；但角色映射、端点派生按 Keycloak 语义调优，且只对 Keycloak 做集成验证。

## 2. 领域调研

### 2.1 生态现状（2026-07 逐项验证）

1. **Keycloak 官方 Spring 适配器已死**：`keycloak-spring-boot-starter` / `keycloak-spring-security-adapter` 在 KC 17（2022）弃用、KC 20 移除；官方明确推荐 Spring Security 原生 OAuth2 支持（[Keycloak discussion #10187](https://github.com/keycloak/keycloak/discussions/10187)）。当前 Keycloak 26.7.0（[发布公告](https://www.keycloak.org/2026/07/keycloak-2670-released)）。**结论：标准路径 = `spring-security-oauth2-resource-server` + `oauth2-jose`，全部由 Boot BOM 管理，父 pom 运行时零新增 pin。**
2. **S2S 取令牌已有官方标准件**：Spring Security 6.4+ 的 `OAuth2ClientHttpRequestInterceptor` 直接挂 `RestClient` 做 client_credentials（[Spring 官方博客](https://spring.io/blog/2024/10/28/restclient-support-for-oauth2-in-spring-security-6-4/)），与 facility `HttpClients` 天然契合——这是 P2 翼的技术基础。
3. **测试基建成熟**：[dasniko/testcontainers-keycloak](https://github.com/dasniko/testcontainers-keycloak) 4.1.1（2026-01，默认对齐 KC 26.5）支持 realm 导入夹具，对位本仓库 PG/MinIO Testcontainers IT 先例；无 Docker 路径用 WireMock 假 JWKS + 自签 RSA JWT（复用已 pin 的 wiremock 3.13.2）。

### 2.2 Keycloak 对接的三个著名坑（模块要吃掉的复杂度）

| # | 坑 | 模块对策 |
|---|---|---|
| 1 | 角色在私有 claim：realm 角色在 `realm_access.roles`，client 角色在 `resource_access.{client-id}.roles`，Spring 默认只映射 `scope` | `KeycloakJwtAuthenticationConverter` 统一映射（§4.2） |
| 2 | KC 默认签发的 access token `aud` **不含** API 的 client-id（默认只有 `account`），盲开 audience 校验必然全拒 | audience 校验默认关、显式配置才启用；KC 侧 audience mapper 配法写入 USAGE（§6） |
| 3 | Spring 默认 principal 名取 `sub`（UUID），业务日志/审计想要的是 `preferred_username`；且 Security Filter 层的 401/403 绕过 MVC 全局异常处理器，默认响应体与 facility `BaseResponse` 风格割裂 | principal claim 可配、默认 `preferred_username`（缺失回落 `sub`）；自带 EntryPoint/DeniedHandler 写 `BaseResponse` JSON（§4.3） |

### 2.3 "是否一并引入认证客户端"评估（本模块立项时的核心问题）

"认证客户端"在 Keycloak 语境下是三种不同的东西，逐一裁：

| 含义 | 场景 | 评估 | 裁定 |
|---|---|---|---|
| ① 服务间 client_credentials（机器客户端） | 服务 A 以自己的身份调服务 B | 微服务 + Keycloak 架构的高频刚需；无会话、无重定向，风险低；Spring 标准件已有（§2.1-2），但 AuthorizedClientManager 串接、命名客户端注册表、fail-fast、错误 Result 化仍有几十行样板，模块化价值明确 | **P2**（用户拍板 R1：P1 仅资源服务器，S2S 按业务牵引再上；届时随取令牌 API 一并引入 `AuthError` sealed 类型，见 §4.4） |
| ② 交互式登录（authorization_code / OIDC Login） | 服务端渲染页面、BFF 网关替浏览器用户登录 | 拖入完全不同的问题域：会话状态、CSRF、front/back-channel 登出传播、登录页——与本仓库无状态 REST API 定位冲突；Spring 官方配置本已很薄，再包装增值低、SecurityFilterChain 碰撞风险高 | **排除**。逃生舱：消费方自建 chain 时模块 chain 自动退让（§5.3），登录翼可完全自持 |
| ③ Keycloak Admin Client（用户/域管理） | 管理后台程序化建用户、改角色 | 属"身份管理"而非"鉴权"，关注点不同；`keycloak-admin-client` 与 KC 服务器版本强耦合，跟版成本高 | **排除 P1**，写入演进路线（§10），业务出现再评估独立模块 |

### 2.4 裁定记录（2026-07-09 用户拍板）

- **R1 客户端范围**：仅资源服务器。S2S client_credentials 翼降 P2；交互式登录与 Admin Client 排除。
- **R2 Realm 形态**：P1 单 realm 单 issuer；多 issuer（`JwtIssuerAuthenticationManagerResolver`）留 P2 演进位，不留死桩。
- **R3 用户暴露**：`AuthContext` 门面（读 `SecurityContextHolder`）为正门；另提供**默认关**的桥接开关，开启后同步填充 facility `SessionUserHolder`（兼容存量习惯，符合"副作用子开关默认关"哲学）。
- **R4 装配方式**：方案 A——`toolbox.auth.*` 自有配置域，本地派生 Keycloak 端点，自建 `JwtDecoder`，Boot 官方装配自动退让（§5.1 记录 A/B 取舍）。
- **R5 缺配置行为**：引 jar + enabled + 缺必填 ⇒ **启动期失败**（带配置引导）。这是对 ADR-0018"空 Registry 兜底"的**有意偏离**，理由见 §5.4。

## 3. 与 server-facility 的关系

| 复用 | 用途 |
|---|---|
| `JsonUtil` | EntryPoint/DeniedHandler 序列化 `BaseResponse` |
| `BaseResponse`（facility web） | 401/403 响应体形态对齐全仓统一响应 |
| `LogUtil` | 鉴权失败/装配日志 |
| `LocaleUtil` + 模块 i18n bundle | 401/403 消息多语言（键前缀 `toolbox.auth.`，参与 facility 聚合池） |
| `SessionUserHolder` | opt-in 桥接目标（R3） |

**Result 豁免披露（对齐全仓约定的显式记录）**：P1 对外没有"可失败的 API 操作"——401/403 是协议边界行为（filter 层写响应），`AuthContext.current()` 返回 `Optional`（未登录是常态不是错误）。故 **P1 不引入 `AuthError` sealed 类型与 Result 返回面**；P2 的 S2S 取令牌是第一个可失败 API 操作，届时按约定引入。此豁免与 database 豁免（异常语义保事务）性质不同：不是"例外"，是"尚无适用对象"。

依赖方式遵守 00-overview §1.1：只用 facility 静态门面与值类型，不注入 facility bean，模块可独立测试。

## 4. 核心抽象设计

```
cn.code91.toolbox.auth/
├── core/
│   ├── CurrentUser.java            # record：sub、username、email、name、realmRoles、clientRoles、rawClaims
│   └── AuthContext.java            # 静态门面：current()、hasRole()、hasClientRole()、isAuthenticated()
├── jwt/
│   ├── KeycloakEndpoints.java      # server-url + realm → issuer / jwks-uri 派生（纯函数）
│   └── KeycloakJwtAuthenticationConverter.java   # Jwt → JwtAuthenticationToken（角色映射 + principal）
├── web/
│   ├── AuthEntryPoint.java         # 401 → BaseResponse JSON + WWW-Authenticate 头
│   ├── AuthAccessDeniedHandler.java# 403 → BaseResponse JSON
│   └── SessionUserBridgeFilter.java# opt-in：填充/清理 SessionUserHolder（public，供自建 chain 复用）
└── autoconfigure/
    ├── ToolboxAuthAutoConfiguration.java
    └── ToolboxAuthProperties.java  # + nested：Keycloak/Jwt/SecurityChain/MethodSecurity/Bridge
```

### 4.1 `core`：CurrentUser 与 AuthContext

```java
public record CurrentUser(
        String sub,                          // KC 用户 UUID（稳定主键）
        String username,                     // principal-claim 解析结果（默认 preferred_username，缺失回落 sub）
        String email,                        // 可空
        String name,                         // 可空（KC name claim）
        Set<String> realmRoles,              // realm_access.roles 原始名（未加 ROLE_ 前缀）
        Map<String, Set<String>> clientRoles,// resource_access 全量（不受 client-roles 映射白名单限制，读原始 claim）
        Map<String, Object> rawClaims) { }   // 完整 claim 逃生舱
```

`AuthContext` 读 `SecurityContextHolder`，`authentication.getPrincipal() instanceof Jwt` 时适配为 `CurrentUser`（按需构建，无缓存——record 构建成本低于一次 Map 拷贝，不值得引入请求级缓存复杂度）：

```java
Optional<CurrentUser> current();
boolean isAuthenticated();
boolean hasRole(String realmRole);              // 语义：realm 角色原始名（不带 ROLE_ 前缀）
boolean hasClientRole(String clientId, String role);
```

**边界**：`AuthContext` 依赖 `spring-security-core`（`SecurityContextHolder`/`Authentication`）与 `spring-security-oauth2-jose`（`org.springframework.security.oauth2.jwt.Jwt` 值类型）。实现细节钉死：适配判定用 `getPrincipal() instanceof Jwt`，**不**触碰 `JwtAuthenticationToken`（那是 spring-security-oauth2-resource-server 的类型），core 依赖面因此收窄到上述两件。ArchUnit 第三条规则（core 门面不依赖可选 SDK）按模块实情调整为：**core 允许依赖 spring-security-core 与 spring-security-oauth2-jose，禁止依赖 servlet / spring-security-web / spring-security-config / spring-security-oauth2-resource-server 类型**。理由：本模块整体以 security 在场为 L1 前提（缺 security 类模块零装配），业务代码 import `AuthContext` 时 security 必然在 classpath——与 database 模块 core 允许 spring-jdbc 同一先例；收窄禁令仍保证门面在非 web 测试上下文可加载。

### 4.2 `jwt`：端点派生与角色映射

**KeycloakEndpoints**（纯函数，两处消费：decoder 工厂、USAGE 文档示例）：

- `issuer = {server-url 去尾斜杠}/realms/{realm}`（KC 17+ Quarkus 发行版无 `/auth` 前缀；若消费方跑遗留发行版或配了 `http-relative-path`，把前缀并入 `server-url` 即可，USAGE 写明）
- `jwksUri = {issuer}/protocol/openid-connect/certs`（KC 稳定公开契约；本地派生 ⇒ 启动不发 OIDC discovery 请求，见 §5.4 懒加载设计）

**KeycloakJwtAuthenticationConverter**（`Converter<Jwt, AbstractAuthenticationToken>`）——authorities 为三路并集，全程对缺失 claim 空安全（KC 可配置剥除任一结构）：

| 来源 | 条件 | 映射 |
|---|---|---|
| `realm_access.roles` | `map-realm-roles=true`（默认） | `ROLE_{role}`（原始大小写，不转换） |
| `resource_access.{client}.roles` | `client` ∈ `client-roles` 白名单（默认空=不映射） | `ROLE_{role}` 扁平化；跨 client 重名会合并——**坦诚披露**于 Javadoc 与 USAGE，需要区分时业务改用 `AuthContext.hasClientRole(client, role)`（读原始结构，无此歧义） |
| `scope`（空格分隔） | `map-scopes=true`（默认） | `SCOPE_{scope}`（保留 Spring 原生语义，`hasAuthority("SCOPE_x")` 可用） |

principal 名：`principal-claim` 配置项（默认 `preferred_username`），缺失回落 `sub`。产出 `JwtAuthenticationToken(jwt, authorities, principalName)`。

### 4.3 `web`：错误响应与桥接

**AuthEntryPoint（401）/ AuthAccessDeniedHandler（403）**：

- 响应体：facility `BaseResponse`（`code=401/403`，`message` 走 i18n 键 `toolbox.auth.unauthorized` / `toolbox.auth.forbidden`，`description` 携带安全无害的细节如 `invalid_token`/`insufficient_scope` 错误码——**不回显 token 内容与校验失败内因**，防探测）。
- 同时保留标准 `WWW-Authenticate: Bearer error=...` 头（RFC 6750），不破标准客户端；Content-Type `application/json;charset=UTF-8`。
- 实现只依赖 `jakarta.servlet` + facility `JsonUtil`，不引 spring-web。
- 在 chain 中双挂：`oauth2ResourceServer(...)` 配置器内（bearer 路径）与 `exceptionHandling(...)`（非 bearer 路径，如匿名访问受保护端点），保证 401 形态处处一致。

**SessionUserBridgeFilter**（`OncePerRequestFilter`，仅 `bridge.session-user.enabled=true` 时加入 chain）：

```java
doFilterInternal(req, res, chain) {
    AuthContext.current().ifPresent(SessionUserHolder::setUser);
    try { chain.doFilter(req, res); }
    finally { SessionUserHolder.clear(); }   // 与 facility SessionUserClearInterceptor 双清无害
}
```

位置：`addFilterBefore(bridge, AuthorizationFilter.class)`（认证已完成、授权与业务尚未开始）。类声明 public：消费方自建 chain 时可自行挂载（§5.3 逃生舱的组成部分）。

### 4.4 错误类型（P1 无，显式豁免）

见 §3 Result 豁免披露。防审查误判专记：全仓"`{Module}Error` sealed interface"命名约定在本模块 P1 **没有适用对象**，不是遗漏；P2 S2S 翼的 `token()` 取令牌 API 是第一个引入点。

## 5. 自动装配设计

### 5.1 装配方式取舍记录（裁定 R4）

| | 方案 A：`toolbox.auth.*` 自有配置域（**采用**） | 方案 B：骑 Boot `spring.security.oauth2.resourceserver.jwt.*` |
|---|---|---|
| 配置归属 | 单一前缀，与基座配置空间硬隔离（00-overview §4.1 哲学） | 劈成两个前缀（issuer 归 Boot、角色映射归 toolbox） |
| 错误暴露 | server-url/realm 语法错误绑定期即炸（§5.4） | issuer 错误滞后到首请求 |
| 心智模型 | Keycloak 原生（server+realm），不拼 URL | Boot 标准知识可迁移 |
| 多 realm 演进 | 配置结构 registry 化即可（P2） | 被 Boot 单 issuer 属性模型卡死 |
| 与 Boot 关系 | 我方注册 `JwtDecoder` bean，Boot `OAuth2ResourceServerAutoConfiguration` 因 `@ConditionalOnMissingBean(JwtDecoder)` 自动退让 | 完全依赖 Boot 装配 |

### 5.2 条件装配分层（L0-L5 对位）

| 层 | 本模块落点 |
|---|---|
| L0 | 引 `auth-enhancement` jar |
| L1 | `@ConditionalOnClass(name="org.springframework.security.oauth2.jwt.JwtDecoder")`（探测 oauth2-jose/resource-server 在场）+ `@ConditionalOnWebApplication(SERVLET)` |
| L2 | `toolbox.auth.enabled`，`matchIfMissing=true` |
| L3 | 无供应商互斥（P1 仅 Keycloak 语义一种形态） |
| L4 | 消费方自声明 `SecurityFilterChain` / `JwtDecoder` / `KeycloakJwtAuthenticationConverter` 任一 bean ⇒ 对应件退让（Seam 分级见 §5.3） |
| L5 | P1 无 SPI 收集点（角色映射定制走 L4 换 converter；authorities 后处理 SPI 属 YAGNI，P2 有真实需求再议） |

### 5.3 装配结构与 Seam 分级

```java
@AutoConfiguration(before = { OAuth2ResourceServerAutoConfiguration.class,
                              SecurityAutoConfiguration.class,
                              UserDetailsServiceAutoConfiguration.class })
@ConditionalOnClass(name = "org.springframework.security.oauth2.jwt.JwtDecoder")
@ConditionalOnWebApplication(type = SERVLET)
@ConditionalOnProperty(prefix = "toolbox.auth", name = "enabled", matchIfMissing = true)
@EnableConfigurationProperties(ToolboxAuthProperties.class)
public class ToolboxAuthAutoConfiguration {

    @Bean @ConditionalOnMissingBean          // Seam 2：换校验行为
    JwtDecoder toolboxAuthJwtDecoder(ToolboxAuthProperties props) { ... }   // §5.4

    @Bean @ConditionalOnMissingBean          // Seam 3：换映射行为（自类型条件）
    KeycloakJwtAuthenticationConverter toolboxAuthJwtConverter(ToolboxAuthProperties props) { ... }

    @Configuration @ConditionalOnClass(name = "org.springframework.security.web.SecurityFilterChain")
    @ConditionalOnProperty(prefix = "toolbox.auth.security-chain", name = "enabled", matchIfMissing = true)
    static class SecurityChainConfiguration {
        @Bean @ConditionalOnMissingBean(SecurityFilterChain.class)   // Seam 1（主 Seam）：整链替换
        SecurityFilterChain toolboxAuthSecurityFilterChain(HttpSecurity http, ...) { ... }
    }

    @Configuration @ConditionalOnProperty(prefix = "toolbox.auth.method-security",
                                          name = "enabled", matchIfMissing = true)
    @EnableMethodSecurity
    static class MethodSecurityConfiguration { }   // @Enable 重复声明幂等（同名基础设施 bean 覆盖注册）
}
```

- **Boot 退让链**：本装配 `before` 三个 Boot 官方装配——`JwtDecoder` bean 先注册 ⇒ `OAuth2ResourceServerAutoConfiguration` 退让；`SecurityFilterChain` 先注册 ⇒ `SpringBootWebSecurityConfiguration` 默认 form-login 链退让（`@ConditionalOnDefaultWebSecurity` 不成立）；`JwtDecoder` bean 在场 ⇒ `UserDetailsServiceAutoConfiguration` 退让（不再生成内存用户与启动密码告警）。
- **默认链形态**：`csrf.disable()`（无状态 API）→ `cors(withDefaults())`（有 `CorsConfigurationSource` bean 用之，否则回落 MVC HandlerMappingIntrospector——即 facility 的 CORS WebMvcConfigurer 配置自动生效）→ `sessionManagement(STATELESS)` → `authorizeHttpRequests`（`permit-paths` 逐条 `permitAll()`，`anyRequest().authenticated()`，**fail-closed：不内置任何默认放行路径**，actuator 健康检查等由消费方显式列入）→ `oauth2ResourceServer(jwt: decoder + converter + entryPoint/deniedHandler)` → `exceptionHandling(entryPoint/deniedHandler)` →（bridge 开启时）`addFilterBefore(SessionUserBridgeFilter, AuthorizationFilter.class)`。
- **Seam 分级使用指引**（写入 README）：要改"哪些路径放行/加登录翼" ⇒ Seam 1 自建整链（`security-chain.enabled=false` 或直接声明 bean），decoder/converter 仍可注入复用；只改校验（如换 JWKS 缓存策略） ⇒ Seam 2；只改角色映射 ⇒ Seam 3。

### 5.4 fail-fast 与懒加载的边界（裁定 R5，ADR-0018 有意偏离）

**偏离披露**：ADR-0018"未配置装空 Registry、应用能启动、`get()` 时给引导"对 auth 不适用——auth 没有"`get()` 时机"（每个请求都是使用点），且安全件半配置的两种自然结局都不可接受：静默不装配 = fail-open（最危险），装配但无 decoder = 全量 401 且引导信息只能打在日志里。故裁定：

- **引 jar + `enabled`（默认）+ `server-url`/`realm` 缺失或畸形 ⇒ 启动失败**，异常消息携带最小可用配置样例（对位设计原则 8"凭证/连接类配置错误启动期抛"）。"引了暂不用"的合法出口是 `toolbox.auth.enabled=false`。
- 校验为**程序化校验**（decoder 工厂内显式检查），不依赖 `@Validated`——消费方 classpath 未必有 JSR-303 实现，注解校验会静默跳过。
- **fail-fast 只对"真配置错误"**：issuer/jwks 端点本地派生（§4.2），启动期**不发任何网络请求**；JWKS 首次取用在首个 bearer 请求（Nimbus 缓存 + 未知 kid 自动刷新）。KC 暂时不可达 ⇒ 服务照常启动、bearer 请求 401（`description=jwks_unavailable` 风格错误码），**不**让 IdP 故障联动压垮服务启动。时钟偏移校验默认 60s（`clock-skew` 可配）；签名算法默认 `RS256`（`jws-algorithms` 可配列表，KC realm 可改 ES256 等）。

## 6. 配置设计

```yaml
toolbox:
  auth:
    enabled: true                          # L2 总开关，matchIfMissing=true
    keycloak:
      server-url: https://kc.example.com   # 必填（见 §5.4 缺失行为）；遗留 /auth 前缀发行版并入此值
      realm: myrealm                        # 必填
      issuer-uri:                           # 可选覆写：内外网地址不一致（token 的 iss 是外部地址、JWKS 走内网）时
                                            #   设置后 issuer 校验用此值，JWKS 仍按 server-url 派生
      required-audience:                    # 可选；设置才追加 audience 校验器。KC 默认 aud 不含 API client-id，
                                            #   需 KC 侧配 audience mapper——USAGE 给出完整操作步骤
    jwt:
      principal-claim: preferred_username   # 缺失回落 sub
      clock-skew: 60s
      jws-algorithms: [RS256]
      map-realm-roles: true
      client-roles: []                      # 要映射进 authorities 的 client-id 白名单
      map-scopes: true
    security-chain:
      enabled: true                         # 关掉 ⇒ 只留 decoder/converter，链自己写（Seam 1 的配置形态）
      permit-paths: []                      # 显式放行，requestMatchers(String...) 路径模式语义
                                            #   （如 /actuator/health、/public/**）；空 = 全鉴权（fail-closed）
    method-security:
      enabled: true
    bridge:
      session-user:
        enabled: false                      # R3：opt-in
```

密钥观：本模块配置无凭证（JWKS 是公钥体系），R6 风险（密钥落 yaml）天然不适用；`server-url` 等按惯例仍建议 `${ENV_VAR}` 占位。

## 7. 依赖策略

| 层 | 依赖 | 说明 |
|---|---|---|
| compile | server-facility、spring-boot-autoconfigure、spring-boot、spring-context、spring-beans、spring-core | 房内标准六件 |
| optional | `spring-boot-starter-oauth2-resource-server`（交付物标记，analyze 豁免注释同 starter-mail 先例）；实际内容件：spring-security-config / spring-security-core / spring-security-web / spring-security-oauth2-core / spring-security-oauth2-resource-server / spring-security-oauth2-jose；jakarta.servlet-api（web 翼直接使用） | 全部 Boot BOM 管理，**零新增运行时 pin**；缺类时 L1 兜住 |
| test | starter-test 聚合 + spring-boot-test + junit-jupiter-api + assertj（房内标准）；spring-security-test（`jwt()` post-processor）；spring-boot-starter-web（MockMvc 全链）；wiremock（假 JWKS）；nimbus-jose-jwt（测试铸 token，显式声明——运行期经 oauth2-jose 传递但 optional 不透传）；testcontainers junit-jupiter + **com.github.dasniko:testcontainers-keycloak 4.1.1**；archunit 两件；logback 两件 + slf4j（日志钉测试） | — |

**父 pom 唯一改动**：`<module>auth-enhancement</module>` + `<testcontainers-keycloak.version>4.1.1</testcontainers-keycloak.version>` test 件 pin（Boot BOM 不管理 com.github.dasniko，注释风格同 greenmail/wiremock 先例；其传递的 keycloak-admin-client 26.0.8 仅 test 作用域，不构成运行时 KC 依赖）。

## 8. 使用视角（消费方）

```xml
<dependency>
    <groupId>cn.code91</groupId>
    <artifactId>auth-enhancement</artifactId>
</dependency>
<dependency>   <!-- 按 00-overview 依赖治理第 4 层：消费方显式引 starter，版本 BOM 管 -->
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

```yaml
toolbox.auth.keycloak:
  server-url: ${KC_URL}
  realm: myrealm
```

```java
@GetMapping("/orders")
@PreAuthorize("hasRole('order-admin')")            // realm 角色，方法级安全开箱即用
public BaseResponse<List<Order>> list() {
    CurrentUser user = AuthContext.current().orElseThrow();   // 或注入点位用 @AuthenticationPrincipal Jwt
    ...
}
```

USAGE.md 交付清单：KC 侧准备（建 client、audience mapper 步骤、角色建模建议：realm 角色 vs client 角色的选用）、`permit-paths` 与 actuator、自建 chain 菜谱（含桥接 filter 复用）、真实 KC 手动冒烟清单（不进 CI，同各模块惯例）。

## 9. 测试策略

| 层 | 手段 | 覆盖 |
|---|---|---|
| 装配矩阵 | `ApplicationContextRunner` | 无 security 类 ⇒ 零 bean；`enabled=false` ⇒ 零 bean；非 servlet ⇒ 零 bean；缺必填 ⇒ 启动失败且消息含引导样例；用户自带 chain/decoder/converter ⇒ 对应退让；`security-chain.enabled=false` ⇒ 只剩 decoder/converter；method-security/bridge 开关矩阵 |
| 单测 | 真实 KC token JSON 夹具（含 realm+client 角色、无角色、无 scope、缺 preferred_username 变体） | converter 三路映射与空安全、principal 回落、`KeycloakEndpoints` 派生（含尾斜杠/`/auth` 前缀）、EntryPoint/DeniedHandler 的 JSON shape 与 WWW-Authenticate 头、`CurrentUser` 适配 |
| 集成（无 Docker，主矩阵） | WireMock 假 JWKS + 自签 RSA JWT + MockMvc 全链 | 200（好 token）/401（过期、坏签名、错 issuer、错 audience、无 token）/403（缺角色）/permit-paths 放行/桥接开启后 `SessionUserHolder` 可见且请求后清理/JWKS 不可达 ⇒ 401 非 500 |
| 集成（Docker） | dasniko testcontainers-keycloak 4.1.1 + realm-import.json 夹具（预置用户/角色/client），password grant 取真 token | 真 KC 签发 token 的端到端角色映射、principal=preferred_username、audience mapper 配置后 aud 校验通过——防"自签 token 与真实 KC 形态漂移"（对位 PG/MinIO IT 惯例，`*IT` 命名） |
| 架构 | ArchUnit：包无环；autoconfigure 不被主包依赖；core 依赖白名单（§4.1 调整版：允许 spring-security-core/spring-security-oauth2-jose，禁 servlet/security-web/security-config/oauth2-resource-server） | 结构不腐化 |

jacoco 门禁沿用父 pom 80/80/70。

## 10. 演进路线

| 阶段 | 内容 | 触发条件 |
|---|---|---|
| P2 | **S2S client_credentials 翼**（R1 裁定移出 P1）：命名客户端注册表 + `OAuth2ClientHttpRequestInterceptor` 挂 facility `HttpClients`/`RestClient` + 取令牌 API 返 `Result<T, AuthError>`（`AuthError` sealed 于此进场）；L1 探测 `spring-security-oauth2-client` | 出现第一个服务间调用需求 |
| P2 | 多 issuer / 多 realm（`JwtIssuerAuthenticationManagerResolver` + 配置 registry 化） | 多租户/多环境共存需求 |
| P2 | token relay（收到的用户 token 透传下游） | 网关/编排场景 |
| P3+ | opaque token introspection（KC lightweight access token 场景）、WebFlux 支持 | 明确业务出现 |
| 独立评估 | Keycloak Admin Client（身份管理域，非鉴权域） | 管理后台程序化建户需求出现时，评估独立模块 |
