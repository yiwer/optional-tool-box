# auth-enhancement

Keycloak 资源服务器鉴权：引依赖 + 配 `server-url`/`realm` 两项，即获得 JWT 本地校验、Keycloak
私有角色结构映射（`realm_access`/`resource_access` → `GrantedAuthority`）、开箱即用的无状态安全链、
facility `BaseResponse` 风格的 401/403 JSON 响应与方法级安全（`@PreAuthorize`）。设计全文见
[docs/design/07-auth-enhancement.md](../docs/design/07-auth-enhancement.md)。

**零 Keycloak 运行时依赖**：核心只用 Spring Security 标准件；角色映射与端点派生按 Keycloak 语义调优，
且只对 Keycloak 做集成验证。P1 范围只做资源服务器鉴权——交互式登录（authorization_code/OIDC
Login）、Keycloak Admin Client、S2S client_credentials 客户端不在本阶段（排除理由与演进路线见设计
文档 §2.3/§10）。

## 快速开始

三步：引依赖 + 引擎 starter → 配 `server-url`/`realm` → `@PreAuthorize` 即用。

```xml
<dependency>
    <groupId>cn.code91</groupId>
    <artifactId>auth-enhancement</artifactId>
</dependency>
<!-- 鉴权引擎本身 optional，按 00-overview 依赖治理第 4 层由消费方显式引入，版本由父 pom BOM 收口 -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

```yaml
toolbox:
  auth:
    keycloak:
      server-url: ${KC_URL}    # 必填；缺失/畸形 ⇒ 启动失败，见下方"缺配置行为"
      realm: myrealm            # 必填
```

```java
@GetMapping("/orders")
@PreAuthorize("hasRole('order-admin')")            // realm 角色，方法级安全默认开
public BaseResponse<List<Order>> list() {
    CurrentUser user = AuthContext.current().orElseThrow();   // 正门；或 @AuthenticationPrincipal Jwt
    ...
}
```

不引 `spring-boot-starter-oauth2-resource-server`（L1 缺类不装配）或设
`toolbox.auth.enabled=false`（L2），本模块整体不装配，应用不受影响。

⚠️ 注意：缺引擎类的退出态是**无任何鉴权保护（fail-open）**，不是降级——依赖重构时若 starter 被意外 exclude，应用会静默裸奔。`enabled=false` / `security-chain.enabled=false` 两种退出态则落到 Boot 默认链（fail-closed），性质不同。缓解：缺引擎类但检测到 `toolbox.auth.*` 配置在场时，启动期打一条 WARN（fail-open 探测器，设计 §5.5；`enabled=false` 显式退出不警）——WARN 是提醒不是保护，依赖治理仍是第一道防线。

## 配置速查（`toolbox.auth.*`）

| 键 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关 |
| `keycloak.server-url` | — | 必填；KC 基地址（遗留 `/auth` 前缀发行版并入此值） |
| `keycloak.realm` | — | 必填；realm 名 |
| `keycloak.issuer-uri` | 空 | 可选覆写：内外网地址不一致时 issuer 校验用此值，JWKS 仍按 `server-url` 派生（USAGE §4） |
| `keycloak.required-audience` | 空 | 可选；设置才追加 audience 校验器（KC 默认 aud 不含 API client-id，须 KC 侧配 mapper，USAGE §1） |
| `jwt.principal-claim` | `preferred_username` | principal 名 claim，缺失回落 `sub` |
| `jwt.clock-skew` | `60s` | 时间类校验容忍偏移 |
| `jwt.jws-algorithms` | `[RS256]` | 接受的签名算法，须与 KC realm 一致 |
| `jwt.map-realm-roles` | `true` | `realm_access.roles` → `ROLE_` |
| `jwt.client-roles` | `[]` | 要映射进 authorities 的 client-id 白名单（扁平 `ROLE_`，跨 client 重名合并——需要区分改用 `AuthContext.hasClientRole`） |
| `jwt.map-scopes` | `true` | `scope` → `SCOPE_` |
| `security-chain.enabled` | `true` | 关掉 ⇒ 只留 decoder/converter，链自己写（Seam 1 配置形态，USAGE §3） |
| `security-chain.permit-paths` | `[]` | 显式放行路径（`requestMatchers` 模式）；空 = 全鉴权（fail-closed：不内置任何默认放行路径，含 actuator，USAGE §2） |
| `method-security.enabled` | `true` | `@EnableMethodSecurity`，`@PreAuthorize` 即用 |
| `bridge.session-user.enabled` | `false` | opt-in：同步填充 facility `SessionUserHolder`（兼容存量习惯；副作用子开关默认关） |

## 缺配置行为

引 jar +`enabled`（默认开）+ `server-url`/`realm` 缺失或畸形 ⇒ **启动失败**，异常消息携带最小可用
配置样例（fail-fast，对位 00-overview 设计原则 8）。issuer/JWKS 端点本地派生，构建期不发任何网络
请求——KC 暂时不可达不影响启动，bearer 请求得 401（不会放大为 500）。KC 不可达的 401 与坏 token
的 401 在响应体 `description` 上区分：前者 `jwks_unavailable`（同时打 WARN 一条），后者
`invalid_token`（设计 §4.3）；`WWW-Authenticate` 头恒为标准 `invalid_token` 不扩展。"引了暂不用"
的合法出口是 `toolbox.auth.enabled=false`。

## 可替换 Seam（`@ConditionalOnMissingBean`）

| 级别 | 换什么 | 覆盖方式 |
|---|---|---|
| Seam 1（主 Seam） | 整条安全链 | 自声明 `SecurityFilterChain` bean，或设 `toolbox.auth.security-chain.enabled=false` 完全自建（decoder/converter 仍可注入复用，菜谱见 USAGE §3） |
| Seam 2 | 校验行为（如 JWKS 缓存策略） | 自声明 `JwtDecoder` bean |
| Seam 3 | 角色/scope 映射行为 | 自声明 `KeycloakJwtAuthenticationConverter` bean |
| （附）方法级安全异常出口 | `@PreAuthorize` 拒绝的 403 出口形态 | 自声明 `SecurityExceptionAdvice` bean |

装配前提：`@ConditionalOnClass` 探测 `JwtDecoder` 在场 + Servlet 环境（L1）；`toolbox.auth.enabled`
默认开（L2）；无供应商互斥（L3，P1 仅 Keycloak 语义一种形态）。用户自建整链/decoder/converter 时，
模块对应默认 bean 自动退让（L4）。

## P1 范围（坦诚披露）

- 只做**资源服务器**鉴权：交互式登录、Keycloak Admin Client、S2S client_credentials 客户端不在
  P1（设计文档 §2.3、§10 演进路线）。
- 单 realm 单 issuer；多 issuer/多 realm 留 P2 演进位。
- P1 对外没有"可失败的 API 操作"，故不引入 `AuthError` sealed 类型（设计文档 §3 豁免披露）；
  `AuthContext.current()` 返回 `Optional`——未登录是常态而非错误。

完整使用指引（KC 侧建 realm/client 步骤、audience mapper 操作路径、角色建模建议、自建 chain 菜谱、
真实 KC 手动冒烟清单）见 [USAGE.md](USAGE.md)。
