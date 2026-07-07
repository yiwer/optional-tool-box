# storage-enhancement 设计文档

> 状态：设计稿 v0.1｜配置前缀 `toolbox.storage.*`｜基础包 `cn.code91.toolbox.storage`
> 主参考：beacon-storage（已完整实现：ObjectStore Seam + 阿里 OSS / AWS S3 双 adapter + 预签名 + 分片），架构可直接移植；本设计在其上追加 **local adapter** 与 **上传守卫**

## 1. 模块定位

**对象存储统一 Seam**：业务只面向 `ObjectStore` 接口与逻辑桶名，底下是阿里云 OSS、AWS S3（及一切 S3 兼容：MinIO/腾讯 COS/华为 OBS）或本地文件系统；换云 = 改配置。

- **解决什么**：附件/图片/导出文件的存取直接裸调云 SDK——换云要改代码、错误处理各写各的、本地开发还得连真云、上传入口无安全校验。
- **不做什么**：不做图片处理（缩略图/水印是云厂商或独立服务的事）；不做 CDN 管理；不做文件业务元数据表（归业务库）。

## 2. 领域调研

### 2.1 需求场景盘点

| 场景 | 需要的能力 |
|---|---|
| 附件上传/下载 | put/get/delete/exists，错误可辨识（404 vs 403 vs 网络） |
| 前端直传（不过应用服务器） | 预签名 PUT/GET URL，时效可控 |
| 大文件（视频/备份） | 分片上传（服务端驱动 + 客户端直传驱动两种） |
| 本地开发/CI | 不依赖真云：本地文件系统 adapter 或 MinIO 容器 |
| 多桶治理 | 逻辑桶名（images/documents）与物理桶解耦，切环境只改配置 |
| 上传入口安全 | 文件名清洗、危险扩展名、MIME 嗅探一致性、大小限制 |

### 2.2 provider 格局与选型

| Provider | 接入方式 | 结论 |
|---|---|---|
| 阿里云 OSS | 官方 SDK `aliyun-sdk-oss` 3.18.1 | P1 原生 adapter（国内主流；beacon 已实现） |
| AWS S3 | SDK v2 `software.amazon.awssdk:s3` 2.41.14 | P1 原生 adapter |
| MinIO | **S3 兼容**（endpoint + pathStyleAccess=true） | 不写专用 adapter，走 aws-s3；同时是集成测试载体 |
| 腾讯 COS / 华为 OBS | 均提供 S3 兼容端点 | 先走 aws-s3 兼容覆盖；专用 adapter 仅在需要专有特性时立项（P3 观望） |
| 本地文件系统 | 自研（facility `PathIo`/`Filenames` 之上） | **P1 新增**（beacon 没有）：本地开发零外部依赖 |

### 2.3 从 beacon-storage 吸收的实测经验

1. **阿里云 OSS 不支持预签名分片**（beacon ADR-0035，真 OSS 返 403 SignatureDoesNotMatch）→ 能力差异必须一等公民化：本设计把 `NotSupported` 加入 sealed 错误集，并维护 adapter 能力矩阵（§4.5）。
2. 凭证在 **factory 构造期校验**（ADR-0016）→ 配错启动即失败，不留运行期。
3. `sealed` 跨包 permits 受限 → adapter 接口不用 sealed，用"约定收敛"（仅 adapter 子包实现）。
4. type 互斥用 **nested `@Configuration` + `@ConditionalOnProperty(havingValue=…)`**，未配 type 时空 registry 兜底（ADR-0018）。

## 3. 与 server-facility 的关系

| 复用 | 用途 |
|---|---|
| `Result<T,E>` | 全部 API 返回值；SDK 异常在 ErrorMapper 内收敛，**绝不外抛** |
| `Filenames` | 上传守卫：路径穿越防御、危险扩展名、文件名清洗 |
| `MimeTyping` | 上传守卫：魔数嗅探与声明 Content-Type 一致性 |
| `PathIo` | local adapter 的目录维护（deleteDirectory/directorySize）；对象文件读写走 JDK Files——PathIo 无文件级读写 API（终审修正） |
| `LogUtil` | 操作日志 |
| facility web `HttpFileResponses`（消费方侧） | 下载响应组装——本模块产出流，Web 层用 facility 工具回给浏览器 |

## 4. 核心抽象设计

### 4.1 Seam 接口（`core`，移植 beacon 签名）

```java
public interface ObjectStore {
    String bucketName();                                   // 逻辑桶名

    Result<ObjectMetadata, StorageError> put(String key, InputStream data, long contentLength, String contentType);
    Result<InputStream,   StorageError> get(String key);   // 调用方负责关闭
    Result<List<ObjectKey>, StorageError> list(String prefix, int maxKeys);
    Result<Void,    StorageError> delete(String key);
    Result<Boolean, StorageError> exists(String key);

    Result<PresignedUrl, StorageError> presignedPut(String key, Duration expires, String contentType);
    Result<PresignedUrl, StorageError> presignedGet(String key, Duration expires);

    // 分片：服务端驱动
    Result<MultipartUpload, StorageError> initiateMultipart(String key, String contentType);
    Result<PartETag, StorageError> uploadPart(MultipartUpload upload, int partNumber, InputStream part, long size);
    Result<ObjectMetadata, StorageError> completeMultipart(MultipartUpload upload, List<PartETag> parts);
    Result<Void, StorageError> abortMultipart(MultipartUpload upload);
    // 分片：客户端直传驱动（预签名族，P2；注意 provider 能力差异 §4.5）
    Result<PresignedUrl, StorageError> presignedUploadPart(…);
}

public interface ObjectStoreRegistry {
    ObjectStore get(String logicalBucketName);   // 未配置 → IllegalArgumentException 带配置引导
}
```

### 4.2 错误类型（sealed，7 型 = beacon 6 型 + NotSupported）

```java
public sealed interface StorageError permits
    NotFound, AccessDenied, NetworkError, ValidationError,
    ProviderError, MultipartError,
    NotSupported { … }        // provider 无此能力（如 OSS 预签名分片、local 预签名）——坦诚化而非硬模拟
```

### 4.3 上传守卫（本模块新增，beacon 没有）

```java
public interface StorageGuard {                    // L4 Seam，默认实现串联四检查
    Result<Void, StorageError> check(UploadCandidate candidate);
}
public record UploadCandidate(String filename, String declaredContentType, long size, InputStream peekable) {}
```

默认链：`Filenames` 清洗与危险扩展名 → 大小上限 → 扩展名黑名单（配置叠加） → `MimeTyping` 魔数嗅探与声明比对（不一致 → `ValidationError`）。守卫**不自动内嵌**在 `ObjectStore.put` 里（put 也服务于内部生成文件），而是提供给上传入口显式调用 + 提供 `GuardedObjectStore` 装饰器给"全量强校验"场景选用。

### 4.4 local adapter（本模块新增）

- 根目录 + 逻辑桶 = 子目录；key 经 `Filenames` 清洗后落盘（`PathIo`），防路径穿越；
- `presigned*` 返回 `NotSupported`（本地无签名语义；文档引导用应用自身下载接口）；
- 定位：**开发/测试便利**，非生产建议（USAGE 声明）。

### 4.5 adapter 能力矩阵（随代码维护，进各 adapter 的 README 段落）

| 能力 | aws-s3 | aliyun-oss | local |
|---|---|---|---|
| put/get/list/delete/exists | ✅ | ✅ | ✅ |
| presigned put/get | ✅ | ✅ | ❌ NotSupported |
| 服务端分片 | ✅ | ✅ | ✅（文件拼接实现） |
| 预签名分片（客户端直传） | ✅ | ❌ NotSupported（SDK 限制，beacon 实测） | ❌ NotSupported |

## 5. 自动装配设计

```java
@AutoConfiguration
@ConditionalOnProperty(prefix = "toolbox.storage", name = "enabled",
                       havingValue = "true", matchIfMissing = true)          // L2
@EnableConfigurationProperties(ToolboxStorageProperties.class)
public class ToolboxStorageAutoConfiguration {

    @Bean @ConditionalOnMissingBean                                           // 兜底：未配 type 也能启动
    ObjectStoreRegistry toolboxStorageEmptyRegistry() { … }

    @Bean @ConditionalOnMissingBean
    StorageGuard defaultStorageGuard(ToolboxStorageProperties props) { … }

    @Configuration(proxyBeanMethods = false)                                  // L3 互斥①
    @ConditionalOnProperty(prefix = "toolbox.storage", name = "type", havingValue = "aws-s3")
    @ConditionalOnClass(name = "software.amazon.awssdk.services.s3.S3Client") // L1
    static class AwsS3Config {
        @Bean @ConditionalOnMissingBean
        ObjectStoreRegistry toolboxStorageAwsS3Registry(ToolboxStorageProperties props) { …factory 构造期校验凭证… }
    }

    @Configuration(proxyBeanMethods = false)                                  // L3 互斥②
    @ConditionalOnProperty(prefix = "toolbox.storage", name = "type", havingValue = "aliyun-oss")
    @ConditionalOnClass(name = "com.aliyun.oss.OSS")                          // L1
    static class AliyunOssConfig { … }

    @Configuration(proxyBeanMethods = false)                                  // L3 互斥③
    @ConditionalOnProperty(prefix = "toolbox.storage", name = "type", havingValue = "local")
    static class LocalConfig { … }                                            // 无 L1（零 SDK）
}
```

每个 adapter 自带 `XxxErrorMapper`：SDK 异常 → `StorageError` 的映射集中一处、单测穷尽（beacon 模式）。

## 6. 配置设计

```yaml
toolbox:
  storage:
    enabled: true
    type: aliyun-oss                    # aws-s3 | aliyun-oss | local；未配 → 空 registry
    guard:
      max-size: 100MB
      blocked-extensions: [exe, bat]    # 叠加 facility 危险名单
      verify-mime: true                 # 魔数嗅探比对。裁定：默认值为 false——tika-core 是可选依赖且守卫 fail-closed，未引 tika 时开启会拒绝全部上传；此处为显式开启示例
    aliyun-oss:
      endpoint: https://oss-cn-hangzhou.aliyuncs.com
      # 凭证二选一：ecs-ram-role 优先；否则 AK/SK（env 占位符，风险 R6）
      ecs-ram-role: prod-app-role
      # access-key-id: ${OSS_AK}
      # access-key-secret: ${OSS_SK}
      buckets:
        images:    { name: code91-prod-images }
        documents: { name: code91-prod-docs }
    # --- 本地开发（application-local.yml）---
    # type: local
    # local: { root-dir: ./build/object-store }
    # --- MinIO（application-test.yml，走 aws-s3 兼容）---
    # type: aws-s3
    # aws-s3: { endpoint: http://localhost:9000, region: minio, path-style-access: true, … }
```

## 7. 依赖策略

| 依赖 | scope | 说明 |
|---|---|---|
| `cn.code91:server-facility` | compile | Result/Filenames/MimeTyping/PathIo |
| `spring-boot-autoconfigure` | compile | 装配 |
| `com.aliyun.oss:aliyun-sdk-oss`（3.18.1） | **optional** | type=aliyun-oss 时消费方引入 |
| `software.amazon.awssdk:s3`（2.41.14） | **optional** | type=aws-s3 / MinIO / COS / OBS 兼容 |
| Testcontainers（MinIO） | test | 集成测试 |

两个云 SDK 均缺失时：local type 仍完整可用——保证"本地开发零云依赖"。

## 8. 使用视角（消费方）

```java
// 上传入口：守卫 + 存储
Result<ObjectMetadata, StorageError> r =
    storageGuard.check(new UploadCandidate(file.name(), file.contentType(), file.size(), stream))
        .flatMap(ok -> registry.get("images").put(key, stream, file.size(), file.contentType()));

// 前端直传：签发预签名 URL
Result<PresignedUrl, StorageError> url = registry.get("images").presignedPut(key, Duration.ofMinutes(10), "image/jpeg");

switch (r) {
    case Ok(var meta)                          -> respond(meta);
    case Err(StorageError.NotFound nf)         -> notFound();
    case Err(StorageError.AccessDenied ad)     -> alertOps(ad);      // 凭证/权限问题要告警
    case Err(StorageError.NotSupported ns)     -> fallbackServerUpload();  // 能力差异的显式分支
    case Err(StorageError e)                   -> serverError(e);
}
```

## 9. 测试策略

- **Testcontainers MinIO**（`*IT`）：aws-s3 adapter 全能力回环（put/get/list/delete/presigned/分片），MinIO 即"S3 兼容"路径的持续验证；
- ErrorMapper 单测：各 SDK 异常形态 → 7 型错误映射表穷尽；
- local adapter：路径穿越攻击 key（`../../etc`）必须被 `Filenames` 拦截的安全测试；分片拼接正确性；
- 守卫矩阵：四检查各自触发 + 组合；伪装 MIME 用例；
- 装配矩阵：三 type 互斥（各 type 下另两个 adapter 的类不加载）、缺 SDK 类不装配、未配 type 空 registry、用户覆盖 registry；
- 真云（OSS/AWS）冒烟清单进 USAGE.md，不进 CI。

## 10. 演进路线

| 阶段 | 内容 |
|---|---|
| P1 | ObjectStore/Registry/7 型错误 + aws-s3 与 aliyun-oss adapter（移植 beacon）+ local adapter + 上传守卫 |
| P2 | 分片全家桶（含预签名分片与能力矩阵）+ `GuardedObjectStore` 装饰器 |
| P3 | 桶级 provider（一个应用同时用 OSS 与 S3——registry 由"全局 type"演进为"桶条目 type"，兜底与互斥逻辑随之调整）；COS/OBS 专用 adapter 视需求 |
| P4（观望） | 服务端加密透传、生命周期/归档策略配置化 |

**风险**：
1. P3 的桶级 provider 会改变 L3 装配形态（全局互斥 → 条目级构造）——P1 的 registry 构造代码要预留"按条目 build"的内部结构，避免届时推倒重来；
2. `get()` 返回裸 `InputStream` 的资源泄漏风险——USAGE 强调 try-with-resources，并提供 `getAsBytes`（小文件便捷方法，带大小上限）降低误用面；
3. 阿里云 SDK 与 AWS SDK 传递依赖体积大——已由 optional 策略隔离，消费方只承担所选一家。
