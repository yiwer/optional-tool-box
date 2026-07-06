# database-enhancement 设计文档

> 状态：设计稿 v0.1｜配置前缀 `toolbox.database.*`｜基础包 `cn.code91.toolbox.database`
> 主参考：beacon-database（已完整实现的 ORM 无关数据库 deep module，含 45 条 ADR 沉淀）

## 1. 模块定位

**ORM 无关的数据库增强件**：不替代、不绑定任何 ORM，在 `DataSource` / `NamedParameterJdbcTemplate` 之上补齐四类能力——SQL 观测、轻量注解 CRUD、审计字段填充、字段加解密。

- **解决什么**：server-facility 完全零数据库能力；业务项目各自手写实体↔SQL 映射、各自接 SQL 日志、各自填 created_by/updated_at，重复且不一致。
- **不做什么**：不做连接池（Boot/Druid/Hikari 的事）；不做事务管理（Spring 的事）；不做迁移工具（Flyway 的事，仅在 USAGE 给约定）；不与 MyBatis/JPA 竞争——用了 ORM 的项目仍可只取观测/审计/加密能力。

## 2. 领域调研

### 2.1 能力需求盘点

| 能力 | 场景 | 业界常见做法 |
|---|---|---|
| SQL 观测 | 开发/测试期看真实 SQL 与耗时、定位慢 SQL | p6spy 包装 DataSource（beacon 已验证）；datasource-proxy |
| 轻量 CRUD | 不想引 MyBatis-Plus 的中小服务：实体注解 → insert/updateById/findById… | Spring Data JDBC（约定重）；MyBatis-Plus（绑 MyBatis）；beacon-database L1/L2/L3（贴 spring-jdbc，最轻） |
| 审计填充 | created_at/by、updated_at/by 自动写入 | JPA @PrePersist；MyBatis-Plus MetaObjectHandler；本模块需 ORM 无关方案 |
| 字段加解密 | 手机号/身份证落库加密 | ORM TypeHandler；本模块用 Handler SPI + facility CryptoUtil |
| 多数据源/读写分离 | 中后期规模问题 | dynamic-datasource-spring-boot-starter；自研 AbstractRoutingDataSource |

### 2.2 技术选型结论

| 决策点 | 结论 | 理由 |
|---|---|---|
| CRUD 底座 | **贴 `NamedParameterJdbcTemplate` 做浅 facade**（beacon 路线） | 不重复包装 Spring JDBC；只产它消费的 `SqlParameterSource`/`RowMapper`/SQL 串；接口面极窄 |
| 是否集成 MyBatis-Plus | **否** | 强绑 ORM 违背"可选件"定位；MP 用户引入本模块仍可用观测/审计/加密子能力 |
| 方言策略 | **PG-first + `SqlDialect` seam**，MySQL 列入 P4 | beacon 的类型 handler 体系是 PG 特化的（jsonb/array/inet…），直接移植风险最低；⚠️ 若目标业务以 MySQL 为主，此优先级需反转——**开放问题 R2**，需业务拍板 |
| SQL 观测 | **p6spy（optional）+ BeanPostProcessor 包装** | beacon 已验证；BPP 反射查找缓存在构造期（beacon ADR-0013 的性能教训） |
| 多数据源 | 设计留位，**P4 再做** | 属规模问题，P1-P3 聚焦确定性需求 |

## 3. 与 server-facility 的关系

| 复用 | 用途 |
|---|---|
| `LogUtil` | 慢 SQL 与审计日志输出（自动脱敏） |
| `CryptoUtil`（P3） | `@Encrypted` 字段 AES-256-GCM 加解密 |
| `IdUtil`（可选策略） | `@Id(strategy = SNOWFLAKE)` 主键生成 |
| facility web `PageQuery` / `PageBaseResponse`（P3） | 分页入参/出参与脚手架 Web 层对齐 |
| `JsonUtil`（库 API） | json/jsonb Handler 的序列化（学 beacon：**只用库 API，不注入 facility bean**） |

## 4. 核心抽象设计

### 4.1 分层（移植 beacon-database 的 L1/L2/L3）

| 层 | 入口 | 一句话 |
|---|---|---|
| L1 参数源 | `AnnotatedParameterSource.of(entity)` | 实体 → `SqlParameterSource`（写路径，**双 key**：`:userName` 与 `:user_name` 同时可用，手写 SQL 与生成 SQL 共用一个 ps） |
| L2 SQL + 映射 | `SqlBuilder.insert/updateById/selectById/deleteById(Entity.class)` + `EntityRowMapper<T>` | by-id 四件套 SQL 生成 + ResultSet→实体（Handler 体系驱动，列布局按 ResultSetMetaData 缓存） |
| L3 通用仓库 | `JdbcRepository<T,ID>` | 最小 7 op：insert / updateById / findById / deleteById / existsById / count / list(limit,offset) |

### 4.2 注解（`annotation`）

```java
@Table("t_order")                    // 表名
@Id                                  // 主键（P2 增加 strategy: ASSIGNED | SNOWFLAKE）
@Column(value = "user_name",         // 列名（缺省走 NamingStrategy 驼峰→下划线）
        sqlType = "jsonb")           // 方言类型别名，路由到特化 Handler
@Transient                           // 不参与 SQL
@Encrypted                           // P3：落库加密（走 CryptoUtil Handler）
@AuditField(CREATED_AT | CREATED_BY | UPDATED_AT | UPDATED_BY)   // P3：审计自动填充
```

### 4.3 Handler SPI（照搬 beacon 三索引注册表）

```java
public interface FieldHandler<J> {
    Class<J> javaType();
    J read(ResultSet rs, int columnIndex) throws SQLException;
    Object write(J value) throws SQLException;
    default Set<String> sqlTypeAliases() { return Set.of(); }
}
public interface FieldHandlerRegistryCustomizer { void customize(FieldHandlerRegistry registry); }
```

注册表三索引：byJavaType（精确→装箱→枚举兜底→assignable）、bySqlTypeAlias（`@Column.sqlType` 精确路由）、byAliasFactory（参数化 handler）。**alias 不反盖 byJavaType**（beacon ADR-0023：同一 Java 类型多种 SQL 表示时避免冲突）。无 handler 时**宽松回退**——裸值交给 Spring 默认绑定，容忍未覆盖类型。装配时 `ObjectProvider` 收集 + `freeze()`。

### 4.4 审计填充（本模块新增，beacon 没有）

ORM 无关的落点选在 **L1 写路径**：`AnnotatedParameterSource.of()` 构造时对 `@AuditField` 字段注入值。值来源是 Seam：

```java
public interface AuditValueProvider {
    OffsetDateTime now();                       // 默认实现：系统时钟
    Optional<String> currentUser();             // 默认实现：empty（应用覆盖，如从 SecurityContext/自研上下文取）
}
```

不用 ORM 拦截器 → 只对走本模块 L1/L3 的写生效；用 MyBatis 的项目自行用 MP 的填充器，本模块不越界（文档写明边界）。

### 4.5 设计取舍：为什么这层不用 `Result<T,E>`

数据访问运行在 Spring 事务语义里：`@Transactional` 依赖异常传播触发回滚，且 Spring 已提供 `DataAccessException` 统一转译。把 JDBC 错误包成 Result 会（a）吞掉回滚信号（b）与生态内所有 ORM 的行为相悖。**结论：L1–L3 保留异常语义**（与 beacon-database 一致）；仅"配置期校验"fail-fast 抛 `IllegalStateException`。这是全仓库对"Result 化"原则的唯一豁免，记录于总体设计 §2.3。

### 4.6 方言 seam

```java
public interface SqlDialect {
    String name();                                        // postgresql / mysql(P4)
    String limitOffset(String sql, long limit, long offset);
    void registerBuiltins(FieldHandlerRegistry registry); // 方言特化 handler（PG: jsonb/uuid/inet/array…）
}
```

`SqlBuilder`/`EntityRowMapper` 只面向 `SqlDialect` 接口；P1–P3 仅提供 `PostgresDialect`。

## 5. 自动装配设计

```java
@AutoConfiguration(after = DataSourceAutoConfiguration.class)
@ConditionalOnClass(NamedParameterJdbcTemplate.class)                     // L1
@ConditionalOnProperty(prefix = "toolbox.database", name = "enabled",
                       havingValue = "true", matchIfMissing = true)       // L2
@EnableConfigurationProperties({ToolboxDatabaseProperties.class,
                                ToolboxDatabaseP6spyProperties.class})
public class ToolboxDatabaseAutoConfiguration {

    @Bean @ConditionalOnMissingBean          // L4：命名策略 Seam
    ColumnNamingStrategy columnNamingStrategy() { return ColumnNamingStrategy.CAMEL_TO_UNDERSCORE; }

    @Bean @ConditionalOnMissingBean          // L4：方言 Seam（P1 仅 PG）
    SqlDialect sqlDialect(ToolboxDatabaseProperties props) { … }

    @Bean @ConditionalOnMissingBean          // L4+L5：注册表收集+冻结
    FieldHandlerRegistry fieldHandlerRegistry(SqlDialect dialect,
            ObjectProvider<FieldHandler<?>> handlers,
            ObjectProvider<FieldHandlerRegistryCustomizer> customizers) { … }

    @Bean @ConditionalOnMissingBean
    AuditValueProvider auditValueProvider() { … }         // 默认：时钟 + empty user

    @Bean
    @ConditionalOnProperty(prefix = "toolbox.database.p6spy", name = "enabled", havingValue = "true") // 默认关
    @ConditionalOnClass(name = "com.p6spy.engine.spy.P6DataSource")       // L1：p6spy 可选
    static BeanPostProcessor toolboxDatabaseP6spyWrapper() { … }          // 反射查找缓存在构造期
}
```

要点：p6spy 子开关默认 **false**（有运行时副作用，属"调试型"能力，见总体设计 §3.2 默认值哲学）；BPP 声明为 `static`（避免过早初始化宿主配置类）。

## 6. 配置设计

```yaml
toolbox:
  database:
    enabled: true
    dialect: postgresql          # P1 唯一取值；预留 mysql
    naming-strategy: camel-to-underscore
    p6spy:
      enabled: false             # 仅建议 dev/test 打开；p6spy 细节走 classpath 的 spy.properties
    audit:
      enabled: true              # @AuditField 填充总开关
    slow-sql:
      threshold: 500ms           # P2：慢 SQL 日志阈值（0 关闭），经 LogUtil 输出
```

## 7. 依赖策略

| 依赖 | scope | 说明 |
|---|---|---|
| `cn.code91:server-facility` | compile | LogUtil/CryptoUtil/IdUtil/JsonUtil（库 API） |
| `org.springframework:spring-jdbc` | compile | 本模块的宿主抽象 |
| `spring-boot-autoconfigure` | compile | 装配 |
| `p6spy:p6spy` | **optional** | SQL 观测 |
| `org.postgresql:postgresql` | **optional** | PG-first 定位显式化，应用主动引入 |
| `com.alibaba:druid-spring-boot-3-starter` | **optional** | 推荐连接池但不强加 |
| H2 / Testcontainers-postgresql | test | 双层测试 |

对消费方的强制传递依赖仅：facility + spring-jdbc + autoconfigure。

## 8. 使用视角（消费方）

```java
@Table("t_user")
public class User {
    @Id private Long id;
    @Column("user_name") private String userName;
    @Column(value = "profile", sqlType = "jsonb") private UserProfile profile;
    @AuditField(CREATED_AT) private OffsetDateTime createdAt;
    @Transient private String displayName;
}

@Service
public class UserService {
    private final JdbcRepository<User, Long> repo;   // L3
    private final NamedParameterJdbcTemplate jdbc;   // 复杂查询仍直接用 Spring JDBC

    public void create(User u) { repo.insert(u); }                        // 审计字段自动填充
    public List<User> search(String kw) {                                  // L1 双 key：手写 SQL 复用参数源
        return jdbc.query("select * from t_user where user_name like :userName",
                          AnnotatedParameterSource.of(probe), new EntityRowMapper<>(User.class, registry));
    }
}
```

## 9. 测试策略

- **H2 层**（单测）：命名策略、SQL 生成文本断言、注解解析、审计填充、双 key 参数源；
- **Testcontainers PostgreSQL 层**（`*IT`）：PG 特化 handler（jsonb/uuid/array/inet）、L3 全 op 回环、p6spy BPP 真实包装验证；
- 装配矩阵：无 `NamedParameterJdbcTemplate` 类 → 全不装配；p6spy 缺类/关开关 → BPP 不装配；用户覆盖 `SqlDialect`/`FieldHandlerRegistry` 生效。

## 10. 演进路线

| 阶段 | 内容 |
|---|---|
| P1 | p6spy 观测 + 命名策略 + 注解体系 + L1 参数源（含双 key） |
| P2 | L2 SQL 生成/RowMapper（列布局缓存）+ L3 JdbcRepository + 慢 SQL 日志 + `@Id` 雪花策略 |
| P3 | `@AuditField` 填充 + `@Encrypted`（CryptoUtil Handler）+ 分页对接 facility `PageQuery/PageBaseResponse` |
| P4 | `MysqlDialect`；多数据源路由（`@Routing("read")` + AbstractRoutingDataSource）；读写分离 |

**风险**：
1. 方言取向（开放问题 R2）——若业务定 MySQL 优先，P1 的 handler 内置集要换血，但 L1/L3 与注册表架构不受影响（这正是 `SqlDialect` seam 的价值）；
2. Handler 三索引体系学习曲线陡（beacon 自认的坑）——对策：DESIGN.md 必须带"查找优先级"图 + USAGE 给 5 个以上真实类型示例；
3. 审计填充只覆盖本模块写路径，消费方混用 ORM 时可能误以为全局生效——USAGE 顶部显著声明边界。
