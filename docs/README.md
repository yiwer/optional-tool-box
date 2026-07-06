# optional-tool-box 文档

## 设计文档（docs/design/）

| 文档 | 内容 |
|---|---|
| [00-overview](design/00-overview.md) | **总体设计**：定位、设计原则、即插即用机制（L0–L5 条件分层 / Registry 模式 / SPI 冻结 / 依赖治理四层）、工程规范、父 POM 改造清单、复用矩阵、测试总纲、路线图、风险 |
| [01-compare-enhancement](design/01-compare-enhancement.md) | 对象/集合差异引擎：注解体系、对象图遍历规则、渲染器、对账 API |
| [02-database-enhancement](design/02-database-enhancement.md) | 数据库增强：p6spy 观测、L1/L2/L3 轻量 CRUD、Handler SPI、审计填充、字段加密 |
| [03-llm-enhancement](design/03-llm-enhancement.md) | 大模型接入：多模型注册表、OpenAI 兼容薄适配、结构化输出、用量观测与脱敏 |
| [04-mail-enhancement](design/04-mail-enhancement.md) | 邮件派发：多账号、模板、附件守卫、非生产沙箱、重试限速 |
| [05-storage-enhancement](design/05-storage-enhancement.md) | 对象存储：ObjectStore Seam、OSS/S3/local 三 adapter、上传守卫、预签名与分片 |

## 阅读顺序建议

1. 先读 [00-overview](design/00-overview.md) 的 §2（设计原则）与 §3（即插即用机制）——五个模块共用同一套骨架；
2. 再按需读各模块文档，每篇结构一致：定位 → 领域调研 → facility 复用 → 核心抽象 → 装配 → 配置 → 依赖 → 用法 → 测试 → 路线。

## 调研基线

- **server-facility** `0.1.0-SNAPSHOT`（`D:\Yiwer\code\server-facility`）：Java 21 / Spring Boot 3.5.10 / `cn.code91.facility.*` / `Result<T,E>` 错误通道 / AutoConfiguration.imports 装配
- **beacon-support**（`D:\STELE\beacon\beacon-support`）：思路参考项目；beacon-database 与 beacon-storage 为已实现的即插即用样板，其 ADR 教训已吸收进各模块"风险"章节
