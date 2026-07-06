# mail-enhancement 设计文档

> 状态：设计稿 v0.1｜配置前缀 `toolbox.mail.*`｜基础包 `cn.code91.toolbox.mail`
> 参考：beacon-message 仅空壳无代码；本设计基于 spring-boot-starter-mail（Jakarta Mail）+ beacon 装配范式

## 1. 模块定位

**多账号邮件派发件**：统一的 `MailDispatcher` 接口发文本/HTML/模板/附件邮件，多 SMTP 账号注册表管理，内置重试、限速、发送回调，以及**非生产环境沙箱**（防止测试环境把邮件发给真实客户）。

- **解决什么**：Boot 官方 `spring.mail.*` 只支持**单账号**、无模板、无重试、无防误发；业务项目各自封装且质量参差。
- **不做什么**：不做收邮件（IMAP/POP3）；不做邮件营销/群发平台（退订管理、打开率追踪）；不做站内信/短信/IM（那是 message 域的其它通道，未来若做应为独立模块）。

## 2. 领域调研

### 2.1 需求场景盘点

| 场景 | 需要的能力 |
|---|---|
| 验证码/通知 | 文本或简单 HTML、低延迟、失败重试 |
| 告警邮件 | 高可靠、限速（告警风暴不能打爆 SMTP）、多收件组 |
| 报表投递 | 附件（xlsx/pdf）、附件安全校验、大小限制 |
| 品牌邮件 | HTML 模板 + 变量、内嵌图片（inline cid） |
| 多业务线 | 不同发件账号（notice@ / alert@ / report@），按逻辑名选用 |
| 测试环境 | **绝不能发给真实用户**：白名单域 / 整体重定向到测试邮箱 |

### 2.2 技术选型结论

| 决策点 | 结论 | 理由 |
|---|---|---|
| 发送引擎 | **`JavaMailSenderImpl`（spring-boot-starter-mail，optional）** | Jakarta Mail 是 JVM 事实标准；Wrap, don't replace |
| 多账号 | 自建 `MailAccountRegistry`，每账号一个 `JavaMailSenderImpl` 实例 | Boot 官方装配天然单账号，这正是本模块的增量 |
| 模板引擎 | **内置轻量 `${var}` 渲染为默认**；Thymeleaf 为 optional adapter | 大多数通知邮件用简单占位就够；不为 20% 场景给 100% 用户加依赖 |
| HTTP API 厂商（SendGrid/阿里 DirectMail/腾讯 SES） | P3 以账号级 `type` 扩展 | SMTP 先覆盖绝大多数自建/企业邮局场景 |
| 异步 | `sendAsync` 走 facility 虚拟线程执行器 | 邮件天然慢（秒级），同步只留给需要确认的场景 |

## 3. 与 server-facility 的关系

| 复用 | 用途 |
|---|---|
| `Result<T,E>` | `send()` 返回 `Result<MailReceipt, MailError>` |
| `Filenames` + `MimeTyping` | 附件安全：文件名清洗、危险扩展名拦截、MIME 嗅探与声明一致性校验 |
| `RateLimiterUtil` | 账号级发送限速（SMTP 服务商均有频控） |
| `LocaleUtil` | 多语言邮件主题/模板选择 |
| `LogUtil` | 发送日志（收件人自动脱敏——MaskUtil 邮箱规则） |
| `Async` / 虚拟线程 | `sendAsync` |

## 4. 核心抽象设计

### 4.1 Seam 接口与值对象（`core`）

命名注意：Spring 已占用 `org.springframework.mail.MailSender`，本模块 Seam 取名 **`MailDispatcher`** 避免撞名。

```java
public interface MailDispatcher {
    Result<MailReceipt, MailError> send(MailMessage message);
    CompletableFuture<Result<MailReceipt, MailError>> sendAsync(MailMessage message);
}

public interface MailAccountRegistry {
    MailDispatcher get(String accountName);   // 逻辑账号名；未配置抛带引导的 IllegalArgumentException
    MailDispatcher primary();                 // toolbox.mail.primary 指定
}

// 不可变消息 + builder
MailMessage.builder()
    .to("user@example.com").cc(…).bcc(…).replyTo(…)
    .subject("对账通知")
    .html("recon-notice", Map.of("date", d))     // 模板名 + 变量；或 .text(…) / .rawHtml(…)
    .attach(Attachment.of("对账单.xlsx", bytes))  // 经附件守卫校验
    .inline("logo", logoBytes, "image/png")       // cid 内嵌资源
    .build();

public record MailReceipt(String messageId, String account, OffsetDateTime sentAt) {}
```

### 4.2 错误类型（sealed）

```java
public sealed interface MailError permits
    ConfigError,          // 账号配置缺失/非法（启动期尽量拦，运行期兜底）
    AuthError,            // SMTP 535 认证失败
    ConnectError,         // 连接/握手失败（可重试）
    InvalidRecipient,     // 收件人非法/被服务器拒绝
    TemplateError,        // 模板缺失/渲染失败
    AttachmentRejected,   // 附件守卫拦截（危险扩展名/超大小/MIME 不符）
    RateLimited,          // 本地限速命中（含建议等待时长）
    SandboxBlocked,       // 沙箱拦截（非白名单收件人），携带原收件人便于排查
    ProviderError { … }
```

重试仅针对 `ConnectError`（指数退避，次数可配）；`AuthError`/`InvalidRecipient` 重试无意义，直返。

### 4.3 沙箱（本模块的差异化能力）

```java
// 决策顺序：redirect-to 优先于 allowed；两者都未配则沙箱等于纯放行
if (sandbox.enabled) {
    if (redirectTo != null)          → 全部收件人替换为 redirectTo，原收件人写入邮件头 X-Original-To 与日志
    else if (allowedDomains/allowedRecipients 命中) → 放行
    else                             → 返回 Err(SandboxBlocked(原收件人))
}
```

推荐用法写入 USAGE：`application-dev.yml`/`application-test.yml` 里开启沙箱，生产 profile 不配置——**误发事故的代价远大于这点配置成本**。

### 4.4 SPI（`spi`）

```java
public interface MailTemplateRenderer {           // L4 Seam：默认轻量 ${var}；Thymeleaf adapter 可换
    String name();                                 // simple / thymeleaf
    Result<String, MailError> render(String templateName, Map<String, Object> vars, Locale locale);
}
public interface MailSendListener {                // L5：ObjectProvider 收集，全部回调
    default void onSuccess(MailMessage msg, MailReceipt receipt) {}
    default void onFailure(MailMessage msg, MailError error) {}
}
public interface AttachmentGuard {                 // L4 Seam：默认实现走 Filenames+MimeTyping+大小限制
    Result<Void, MailError> check(Attachment attachment);
}
```

模板文件约定：`classpath:mail-templates/{name}_{locale}.html`，按 `LocaleUtil.currentLocale()` 回退查找。

## 5. 自动装配设计

```java
@AutoConfiguration(after = MailSenderAutoConfiguration.class)
@ConditionalOnClass(name = "jakarta.mail.internet.MimeMessage")           // L1：starter-mail 为 optional
@ConditionalOnProperty(prefix = "toolbox.mail", name = "enabled",
                       havingValue = "true", matchIfMissing = true)       // L2
@EnableConfigurationProperties({ToolboxMailProperties.class, ToolboxMailSandboxProperties.class})
public class ToolboxMailAutoConfiguration {

    @Bean @ConditionalOnMissingBean
    MailTemplateRenderer simpleMailTemplateRenderer() { … }

    @Bean @ConditionalOnMissingBean(name = "thymeleafMailTemplateRenderer")
    @ConditionalOnClass(name = "org.thymeleaf.spring6.SpringTemplateEngine")   // L1：模板引擎可选
    MailTemplateRenderer thymeleafMailTemplateRenderer(…) { … }

    @Bean @ConditionalOnMissingBean
    AttachmentGuard defaultAttachmentGuard(ToolboxMailProperties props) { … }

    @Bean @ConditionalOnMissingBean                                        // 账号注册表（空账号→空 registry 兜底）
    MailAccountRegistry mailAccountRegistry(ToolboxMailProperties props,
            ObjectProvider<JavaMailSender> bootSender,                     // 见 §5.3 共存策略
            ObjectProvider<MailSendListener> listeners,
            MailTemplateRenderer renderer, AttachmentGuard guard) { … }
}
```

### 5.3 与 Boot 官方 `spring.mail.*` 的共存策略（风险 R7）

若消费方已按 Boot 方式配置了 `spring.mail.*`（存在 `JavaMailSender` bean）且 `toolbox.mail.adopt-spring-mail=true`（默认 true）：将该 bean **收编为名为 `spring` 的账号**；若 `toolbox.mail.primary` 未显式配置且自有账号为空，则它就是 primary。这样老项目零迁移接入沙箱/模板/重试能力。

## 6. 配置设计

```yaml
toolbox:
  mail:
    enabled: true
    primary: notice
    adopt-spring-mail: true              # 收编 Boot 官方单账号（若存在）
    accounts:
      notice:
        host: smtp.exmail.qq.com
        port: 465
        ssl: true
        username: notice@code91.cn
        password: ${MAIL_NOTICE_PASSWORD}     # 强制 env 占位符（风险 R6）
        from: notice@code91.cn
        display-name: 系统通知
        rate-limit-per-minute: 60             # 0=不限速
        retry: { max-attempts: 3, backoff: 2s }
      alert:
        host: smtp.exmail.qq.com
        username: alert@code91.cn
        password: ${MAIL_ALERT_PASSWORD}
        rate-limit-per-minute: 20
    attachment:
      max-size: 20MB
      blocked-extensions: [exe, bat, js, vbs]  # 叠加在 facility Filenames 危险名单之上
    sandbox:
      enabled: false                     # 生产恒 false；dev/test profile 打开
      redirect-to: qa-inbox@code91.cn    # 二选一：整体重定向
      # allowed-domains: [code91.cn]     # 或：白名单放行
```

## 7. 依赖策略

| 依赖 | scope | 说明 |
|---|---|---|
| `cn.code91:server-facility` | compile | Result/Filenames/MimeTyping/RateLimiter/LocaleUtil |
| `spring-boot-autoconfigure` | compile | 装配 |
| `org.springframework.boot:spring-boot-starter-mail` | **optional** | 发送引擎；缺失时整模块不装配（L1） |
| `org.thymeleaf:thymeleaf-spring6` | **optional** | 高级模板 adapter |
| `com.icegreen:greenmail-junit5` | test | 内嵌 SMTP 集成测试 |

> starter-mail 标 optional 是刻意的：与"引 jar 即用"稍有摩擦（消费方要多引一个 starter），换来的是本模块与 storage/database 一致的"SDK 由使用方选择引入"原则。README 快速开始里第一行就写清楚。

## 8. 使用视角（消费方）

```java
Result<MailReceipt, MailError> r = mailRegistry.get("alert").send(
    MailMessage.builder()
        .to(oncallList)
        .subject("[P1] 对账差异超阈值")
        .html("recon-alert", Map.of("diffCount", 42, "date", today))
        .build());

if (r instanceof Err(MailError.SandboxBlocked sb)) {
    LogUtil.warn("沙箱拦截了发往 {} 的邮件", sb.originalRecipients());   // 测试环境的正常现象
}
```

## 9. 测试策略

- **GreenMail 集成测试**：真实 SMTP 协议回环——多账号并存、SSL、HTML+附件+inline、认证失败→`AuthError`、连接拒绝→`ConnectError`+重试次数断言；
- 沙箱单测矩阵：redirect / allowed-domains / 双未配 / 关闭，四态 × 各收件人组合；`X-Original-To` 头断言；
- 附件守卫：危险扩展名、伪装 MIME（.jpg 实为 exe，`MimeTyping` 嗅探拦截）、超大小；
- 装配矩阵：无 jakarta-mail 类 → 全不装配；`adopt-spring-mail` 收编行为；模板渲染器 Seam 覆盖。

## 10. 演进路线

| 阶段 | 内容 |
|---|---|
| P1 | 多账号 SMTP + 文本/HTML/附件 + 轻量模板 + 沙箱 + 重试/限速 + 发送回调 |
| P2 | Thymeleaf adapter；多语言模板回退；inline 资源；`X-Original-To` 全链路 |
| P3 | 账号级 `type` 扩展 HTTP API 厂商（阿里 DirectMail / SendGrid）；账号故障转移（failover 组） |
| P4（观望） | 发送队列持久化（依赖 database-enhancement 或消费方自有表）——跨模块特性，待牵引 |

**风险**：
1. SMTP 密码等敏感配置（R6）——USAGE 强制 env 占位符示例，评审时人工核查；
2. 沙箱依赖"生产不配置"这一纪律——对策：沙箱启用时启动期打 WARN 大字日志，且 `redirect-to`/`allowed` 均未配时视为配置错误 fail-fast，避免"以为拦了其实全放行"；
3. 附件流式化（超大附件内存压力）P1 先限制 max-size 兜底，流式留 P3。
