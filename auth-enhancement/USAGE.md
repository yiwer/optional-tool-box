# auth-enhancement 使用指南

速览见 [README.md](README.md)；设计全文见 [docs/design/07-auth-enhancement.md](../docs/design/07-auth-enhancement.md)。

## 1. Keycloak 侧准备

### 1.1 建 realm 与 client

1. Keycloak 管理控制台 → 左上角 realm 选择器 → **Create realm** → 填 realm 名（对应
   `toolbox.auth.keycloak.realm`）。
2. **API 资源服务器 client**（本模块保护的后端服务代表）：Clients → Create client → Client ID
   填一个稳定值（如 `toolbox-api`，本模块不要求它可登录，只需存在于该 realm，作为角色宿主与
   audience mapper 的目标）。Client authentication 按需（是否需要 service account 视业务而定，
   与本模块鉴权链无关）。
3. **签发 token 的 client**（前端应用 / 网关 / 测试脚本用来登录拿 token 的 client）：Clients →
   Create client → 按实际登录方式配置（SPA 常用 Public + Standard flow；机器对机器测试脚本可用
   Direct access grants）。**这个 client 才是下面 audience mapper 要配置的对象**。

### 1.2 建角色

- **realm 角色**（Realm roles → Create role）：进 `realm_access.roles`，`toolbox.auth.jwt.map-realm-roles`
  默认开，扁平映射为 `ROLE_{name}`，`AuthContext.hasRole(name)` / `@PreAuthorize("hasRole('name')")`
  直接可用。
- **client 角色**（Clients → API 资源服务器 client → Roles → Create role）：进
  `resource_access.{client-id}.roles`，只有列入 `toolbox.auth.jwt.client-roles` 白名单的 client 才会
  被扁平映射为 authorities（`ROLE_{name}`，跨 client 重名会合并）；不想受此限制/需要精确区分时用
  `AuthContext.hasClientRole(clientId, role)`（读原始结构，无重名歧义）。

**角色建模建议**：跨多个服务复用的粗粒度职责（如"订单管理员"）用 realm 角色，一次授予、全仓通用；
限定在单个 API 边界内、命名可能与其他服务撞名的细粒度权限（如"某服务的只读账号"）用 client 角色，
并优先用 `hasClientRole` 显式判断而非塞进白名单扁平化。

### 1.3 audience mapper（KC 默认 aud 不含 API client-id，必配）

Keycloak 签发的 access token 默认 `aud` 只有 `account`，不包含 API 的 client-id。若配置了
`toolbox.auth.keycloak.required-audience`，**不配 mapper 会导致所有 token 校验失败（401
invalid_token）**。完整操作路径：

1. Clients → 选中**签发 token 的 client**（不是 API 资源服务器 client）。
2. Client scopes 标签页 → 点进该 client 的 **dedicated scope**（名称形如 `{client-id}-dedicated`）。
3. Add mapper → **By configuration** → 选择 **Audience**。
4. **Included Client Audience** 下拉选中 API 资源服务器的 client-id（如 `toolbox-api`）。
5. **Add to access token** 开关打开 → Save。

配置完成后，该 client 签发的 access token 的 `aud` 数组会包含 API client-id，
`toolbox.auth.keycloak.required-audience=toolbox-api` 才能校验通过。

## 2. `permit-paths` 与 actuator 显式放行

默认链 **fail-closed**：`permit-paths` 之外全鉴权，**不内置任何默认放行路径**——即使是 actuator
健康检查，也必须显式列入才会放行：

```yaml
toolbox:
  auth:
    security-chain:
      permit-paths:
        - /actuator/health
        - /actuator/health/**
        - /public/**
```

`permit-paths` 语义等价于逐条 `HttpSecurity#authorizeHttpRequests` 的
`requestMatchers(pattern).permitAll()`，支持 Ant 风格通配符。忘记把 actuator 加入会导致监控探活
502/401——排障时先查这里。

## 3. 自建 chain 菜谱

要完全自持安全链形态（比如加登录页、改放行策略的组织方式、接入网关鉴权），设
`toolbox.auth.security-chain.enabled=false` 关掉模块默认链——`JwtDecoder` 与
`KeycloakJwtAuthenticationConverter` 仍会装配（Seam 2/3 默认件），可直接注入复用，无需重写角色映射：

```yaml
toolbox:
  auth:
    security-chain:
      enabled: false   # 只留 decoder/converter，链自己写
```

```java
package com.example.security;

import cn.code91.toolbox.auth.jwt.KeycloakJwtAuthenticationConverter;
import cn.code91.toolbox.auth.web.SessionUserBridgeFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

@Configuration(proxyBeanMethods = false)
public class MySecurityConfig {

    @Bean
    SecurityFilterChain myChain(HttpSecurity http, JwtDecoder decoder,
                                 KeycloakJwtAuthenticationConverter converter) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/public/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt
                        .decoder(decoder)
                        .jwtAuthenticationConverter(converter)));
        // 复用模块的桥接 filter（同模块默认链的挂载位置：认证已完成、授权尚未开始）；
        // 仅在需要兼容存量 SessionUserHolder.getUser(...) 习惯代码时加这一行。
        http.addFilterBefore(new SessionUserBridgeFilter(), AuthorizationFilter.class);
        return http.build();
    }
}
```

401/403 响应形态需要与模块默认一致时，自行接入 `AuthEntryPoint`/`AuthAccessDeniedHandler`（均为
public 类，可 `new` 后挂到 `oauth2ResourceServer(...)`/`exceptionHandling(...)`）；不接入则回落
Spring Security 默认响应体（不再是 facility `BaseResponse` 形态）。

## 4. 内外网地址不一致：`issuer-uri` 覆写

token 的 `iss` claim 是外部可见地址，但服务更适合直连内网地址拉 JWKS（更快/不经反代）时，覆写
`issuer-uri`；JWKS 地址仍按 `server-url` 派生，两者可以不同：

```yaml
toolbox:
  auth:
    keycloak:
      server-url: http://keycloak.internal:8080        # 内网直连地址，JWKS 从这里拉取
      realm: myrealm
      issuer-uri: https://auth.example.com/realms/myrealm   # 外部地址，token 的 iss 按此校验
```

不设置 `issuer-uri` 时，issuer 校验与 JWKS 拉取都按 `{server-url}/realms/{realm}` 派生，两者必须
是同一地址（token 由该地址直接签发）。

## 5. 真实 KC 手动冒烟清单（不进 CI）

CI 只跑 WireMock 假 JWKS 矩阵与自带 `testcontainers-keycloak` 的一次性容器 IT；换成长期运行的真实
Keycloak 环境前，人工过一遍：

- [ ] 把 `toolbox.auth.keycloak.server-url`/`realm` 换成真实环境地址与 realm（`${KC_URL}` 环境变量
      占位，不落 yaml 明文）
- [ ] 不带 token 访问受保护端点 → **401**，响应体是 `BaseResponse` JSON 形态，带
      `WWW-Authenticate: Bearer` 头
- [ ] 用真实用户登录拿到的合法 token 访问 → **200**，`AuthContext.current()` 能取到正确的
      sub/username/realm 角色
- [ ] 用无对应角色的用户 token 访问 `@PreAuthorize` 保护端点 → **403**，
      `WWW-Authenticate: Bearer error="insufficient_scope"`
- [ ] 角色映射抽查：realm 角色出现在 `AuthContext.hasRole`/`CurrentUser.realmRoles()`；client 角色
      按白名单扁平（`ROLE_`）与 `hasClientRole` 两条路径分别核对，确认无跨 client 重名误判
- [ ] 若配置了 `required-audience`：确认已配 audience mapper 的 client 签发的 token 通过校验，未配
      mapper 的旧 client token 被拒（401 invalid_token）
- [ ] `permit-paths` 命中的路径匿名可访问，未命中路径全部拦截（抽查 fail-closed 没有意外放行）
