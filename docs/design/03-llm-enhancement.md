# llm-enhancement 设计文档

> 状态：设计稿 v0.1｜配置前缀 `toolbox.llm.*`｜基础包 `cn.code91.toolbox.llm`
> ⚠️ 工程修正项：现 pom 的 artifactId 为 `llm-enhance`，需改为 `llm-enhancement`（见总体设计 §5.1）

## 1. 模块定位

**多模型注册表 + 薄协议适配**：让业务用一个窄接口（`LlmClient`）调用任意大模型完成对话、结构化抽取，错误 Result 化、用量可观测、敏感内容默认脱敏。

- **解决什么**：各服务各自裸调 HTTP 接大模型——密钥散落、超时/重试/限流各写各的、日志把用户数据原文打出去、换模型要改代码。
- **不做什么**：不做 Agent/编排/RAG 框架（P1-P2 明确不做，留给 Spring AI 桥接或上层）；不做提示词平台（仅提供 classpath 模板加载）；不做模型代理网关。

## 2. 领域调研

### 2.1 需求场景盘点

| 场景 | 需要的能力 |
|---|---|
| 文本摘要/润色/翻译 | 同步 chat、超时与重试 |
| 信息抽取（工单→结构化字段） | **结构化输出**（JSON → 强类型 bean）、schema 校验失败可辨识 |
| 内容审核辅助 | 内容过滤类错误可辨识（`ContentFiltered`） |
| 客服/问答流 | 流式 SSE（P2） |
| 成本治理 | token 用量按模型/业务方统计（`UsageListener`） |
| 合规 | 请求日志脱敏（身份证/手机号进提示词是常态） |

### 2.2 协议与框架格局

- **OpenAI 兼容协议已是事实标准**：DeepSeek、通义 Qwen（DashScope 兼容模式）、Moonshot/Kimi、智谱 GLM、Ollama/vLLM 本地部署，全部提供 `/v1/chat/completions` 兼容端点。**一个适配器可覆盖国内外主流 + 私有化部署**。
- **Anthropic Claude** 为独立 Messages API（企业网关通常也会做 openai-compat 转换）。
- **Spring AI**（beacon 管理的 BOM 为 1.1.4，已 GA）：官方抽象全（ChatClient/Advisor/多 provider），但依赖面大、版本仍在快速演进。
- **LangChain4j**：能力全，但引入一整套链式编程模型，超出本模块定位。

### 2.3 技术选型结论

| 决策点 | 结论 | 理由 |
|---|---|---|
| P1 协议 | **自研 OpenAI 兼容薄适配**（基于 facility HttpClients + JDK HttpClient） | 覆盖面最大、依赖为零、完全掌控错误映射与脱敏挂点 |
| Spring AI | **P3 作为可选桥接 adapter**，不是底座 | 让需要多模态/exotic provider 的项目接入，同时不把它的依赖树强加给所有人 |
| 每模型独立选型 | `type` 放在 **model 条目级**（非全局互斥） | 与 storage 不同：多模型天然共存（主力 DeepSeek + 兜底 Qwen），全局互斥不成立 |
| 流式实现 | JDK `java.net.http.HttpClient` 直连 SSE | facility 的 `HttpClients`（RestClient）同步好用但不适合流式（风险 R4）；JDK HttpClient 零新依赖 |

## 3. 与 server-facility 的关系

| 复用 | 用途 |
|---|---|
| `HttpClients` / RestClient bean | 同步 chat 调用通道 |
| `MaskUtil` | 请求/响应日志脱敏（默认开） |
| `RateLimiterUtil` | 模型级客户端 QPS 护栏（厂商侧 429 前先自控） |
| `JsonUtil` | 结构化输出解析（CANONICAL 命名空间） |
| `Async<T>` / 虚拟线程 | `chatAsync` |
| `Result<T,E>` | 全部 API 返回值 |

## 4. 核心抽象设计

### 4.1 Seam 接口与值对象（`core`）

```java
public interface LlmClient {
    Result<ChatResponse, LlmError> chat(ChatRequest request);
    <T> Result<T, LlmError> chatStructured(ChatRequest request, Class<T> targetType);  // JSON mode + 解析 + 校验
    Result<Void, LlmError> chatStream(ChatRequest request, StreamListener listener);   // P2
}

public interface LlmClientRegistry {
    LlmClient get(String modelName);      // 逻辑模型名，未配置抛带引导的 IllegalArgumentException
    LlmClient primary();                  // toolbox.llm.primary 指定
}

public record ChatRequest(List<Message> messages, ChatOptions options) { /* builder */ }
public record Message(Role role, String content) {}          // SYSTEM / USER / ASSISTANT
public record ChatOptions(Double temperature, Integer maxTokens, Duration timeout, …) {}
public record ChatResponse(String content, Usage usage, String finishReason, String rawModel) {}
public record Usage(int promptTokens, int completionTokens, int totalTokens) {}

public interface StreamListener {
    void onDelta(String delta);
    void onComplete(ChatResponse finalResponse);
    void onError(LlmError error);
}
```

### 4.2 错误类型（sealed，pattern matching 穷尽）

```java
public sealed interface LlmError permits
    AuthError,                 // 401/403：密钥无效
    RateLimited,               // 429：含 retryAfter，可自动重试
    ContextLengthExceeded,     // 提示词超窗
    ContentFiltered,           // 内容安全拦截（业务通常要单独文案）
    SchemaMismatch,            // 结构化输出解析/校验失败（含原始文本，便于排查）
    Timeout, NetworkError,     // 可重试类
    ProviderError { … }        // 未归类的厂商错误（保留 providerCode）
```

重试策略内置于 adapter：仅对 `RateLimited`（尊重 retryAfter）/`Timeout`/`NetworkError` 重试，指数退避，次数可配；其余错误一律直返（内容过滤重试无意义且烧钱）。

### 4.3 提示词模板（`prompt`）

```java
public interface PromptTemplateLoader { Optional<String> load(String name); }   // SPI；内置 classpath: prompts/*.md
public final class PromptTemplates {
    public static Result<String, LlmError> render(String name, Map<String, Object> vars);  // ${var} 占位符
}
```

只做"模板在代码库里、随版本走"这一层；模板平台化（热更新/灰度）超出定位，不做。

### 4.4 观测与脱敏

```java
public interface UsageListener {     // SPI：ObjectProvider 收集，全部回调
    void onUsage(String modelName, Usage usage, Duration latency, String traceId);
}
```

- 请求/响应日志经 `MaskUtil` 脱敏后走 `LogUtil`（`toolbox.llm.log.mask-content=true` 默认开；`log.enabled=false` 可整体关）。
- traceId 取自 facility `TraceIdFilter` 的 MDC，天然串起"一次请求→N 次模型调用"。

### 4.5 adapter 结构

```
llm/
├── core/  spi/  prompt/
├── openai/                      # P1：OpenAI 兼容协议
│   ├── OpenAiCompatibleClient   #   同步走 RestClient；流式走 JDK HttpClient(SSE)
│   └── OpenAiErrorMapper        #   HTTP 状态/错误体 → LlmError（映射表进单测）
├── springai/                    # P3：桥接 Spring AI ChatModel（optional 依赖）
└── autoconfigure/
```

每个 model 条目按其 `type` 实例化对应 adapter；registry 内各条目独立（一个 registry 可同时含 openai-compatible 与 spring-ai 条目）。

## 5. 自动装配设计

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "toolbox.llm", name = "enabled",
                       havingValue = "true", matchIfMissing = true)      // L2
@EnableConfigurationProperties(ToolboxLlmProperties.class)
public class ToolboxLlmAutoConfiguration {

    @Bean @ConditionalOnMissingBean                                       // 空兜底：无 models 配置也能启动
    LlmClientRegistry llmClientRegistry(ToolboxLlmProperties props,
                                        ObjectProvider<UsageListener> listeners) {
        // 遍历 props.models：按 type 构造 adapter，织入 重试/限流/脱敏日志/用量回调 装饰链
    }

    @Bean @ConditionalOnMissingBean
    PromptTemplateLoader classpathPromptTemplateLoader() { … }
}
```

- P1 无 L1 条件（OpenAI 兼容 adapter 零第三方依赖）；P3 的 spring-ai 条目类型追加 `@ConditionalOnClass(name="org.springframework.ai.chat.model.ChatModel")` 的嵌套装配。
- 启动期 fail-fast：model 条目缺 `base-url`/`api-key` → 抛 `IllegalStateException` 指明配置路径（学 beacon-storage 凭证前置校验）。

## 6. 配置设计

```yaml
toolbox:
  llm:
    enabled: true
    primary: deepseek                    # LlmClientRegistry.primary() 的指向
    log:
      enabled: true
      mask-content: true                 # MaskUtil 脱敏，默认开
    models:
      deepseek:
        type: openai-compatible
        base-url: https://api.deepseek.com/v1
        api-key: ${DEEPSEEK_API_KEY}     # 强制 env 占位符约定（风险 R6）
        model: deepseek-chat
        temperature: 0.2
        timeout: 60s
        max-retries: 2
        rate-limit-qps: 5                # facility RateLimiterUtil 客户端护栏，0=关闭
        json-mode: false                 # chatStructured 附带 response_format JSON mode 提示（P2 接线；仅部分兼容端点支持）
      local-qwen:
        type: openai-compatible
        base-url: http://vllm.internal:8000/v1
        api-key: none
        model: qwen2.5-32b-instruct
```

## 7. 依赖策略

| 依赖 | scope | 说明 |
|---|---|---|
| `cn.code91:server-facility` | compile | HttpClients/MaskUtil/RateLimiter/JsonUtil/Async |
| `spring-boot-autoconfigure` | compile | 装配 |
| `org.springframework.ai:spring-ai-client-chat`（等） | **optional**（P3） | 桥接 adapter 才需要；版本走父 pom import 的 `spring-ai-bom` |
| `org.wiremock:wiremock` | test | 模拟 OpenAI 兼容端点（含 SSE 分帧） |

P1-P2 对消费方的强制传递依赖仅 facility + autoconfigure —— 这是"薄适配"选型的直接收益。

## 8. 使用视角（消费方）

```java
// 结构化抽取：工单文本 → 强类型
public record TicketInfo(String category, String urgency, List<String> entities) {}

var prompt = PromptTemplates.render("ticket-extract", Map.of("text", ticketText)).orElseThrow();
Result<TicketInfo, LlmError> r = registry.primary()
        .chatStructured(ChatRequest.user(prompt), TicketInfo.class);

switch (r) {
    case Ok(TicketInfo info)                -> save(info);
    case Err(LlmError.RateLimited rl)       -> retryQueue.push(task, rl.retryAfter());
    case Err(LlmError.ContentFiltered cf)   -> markManualReview(ticketId);
    case Err(LlmError e)                    -> alert(e);   // 其余统一告警
}
```

## 9. 测试策略

- WireMock 契约测试：正常响应、429+Retry-After、超时、错误体各形态 → `OpenAiErrorMapper` 映射表全覆盖；SSE 分帧/半包/中断（P2）；
- `chatStructured`：合法 JSON、带 markdown 围栏的 JSON（现实中常见，需剥壳）、非法 JSON → `SchemaMismatch` 携带原文；
- 装配矩阵：无 models 配置 → 空 registry 可启动；缺 api-key → 启动失败且报错信息含配置路径；用量监听器多实例全回调；
- 脱敏验证：含手机号的 prompt 落日志后必须已脱敏（断言日志输出）。

## 10. 演进路线

| 阶段 | 内容 |
|---|---|
| P1 | OpenAI 兼容同步 chat + chatStructured + 错误映射 + 重试/限流/脱敏/用量 + 模板 |
| P2 | SSE 流式（JDK HttpClient）+ chatAsync（虚拟线程） |
| P3 | Spring AI 桥接 adapter（optional）；Anthropic 原生 type（若网关兼容层不够用） |
| P4（观望） | embedding + 向量库薄封装；function calling。均待真实业务牵引，避免为想象中的需求造框架 |

**风险**：
1. 生态漂移（R3）——协议选 OpenAI 兼容这条"最大公约数"，adapter 面积小、可弃可换；
2. SSE 细节多（半包、注释帧、`[DONE]` 哨兵、连接中断恢复）——P2 单独排期并用 WireMock 分帧用例压住；
3. 结构化输出的现实鲁棒性（模型偶发输出围栏/前后缀）——解析器必须内置"剥壳"步骤并在 `SchemaMismatch` 中保留原文。
