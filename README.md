# optional-tool-box

server-facility 脚手架的**可选功能工具箱**：五个相互独立、按需引入、自动装配的增强模块。引入哪个模块，哪个能力就生效；不引入则完全不存在。

| 模块 | 定位 | 状态 |
|---|---|---|
| [compare-enhancement](docs/design/01-compare-enhancement.md) | 对象/集合差异对比（审计、对账），零第三方依赖 | 设计中 |
| [database-enhancement](docs/design/02-database-enhancement.md) | ORM 无关数据库增强：SQL 观测、轻量 CRUD、审计填充、字段加密 | 设计中 |
| [llm-enhancement](docs/design/03-llm-enhancement.md) | 大模型接入：多模型注册表、OpenAI 兼容适配、结构化输出 | 设计中 |
| [mail-enhancement](docs/design/04-mail-enhancement.md) | 邮件派发：多账号、模板、非生产沙箱防误发 | 设计中 |
| [storage-enhancement](docs/design/05-storage-enhancement.md) | 对象存储统一抽象：OSS / S3 兼容 / 本地三 adapter | 设计中 |

## 文档

- **[总体设计](docs/design/00-overview.md)** —— 定位、即插即用机制、工程规范、路线图（先读这篇）
- [文档索引](docs/README.md)

## 基线

Java 21 · Spring Boot 3.5.10 · 依赖 [server-facility](../server-facility) `0.1.0-SNAPSHOT` · 配置前缀 `toolbox.{module}.*` · 基础包 `cn.code91.toolbox.{module}`
