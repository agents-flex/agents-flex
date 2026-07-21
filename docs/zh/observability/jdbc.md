<div v-pre>

# JDBC 可观测数据持久化

## 模块边界

`agents-flex-observability-jdbc` 提供标准 OpenTelemetry `SpanExporter` 和 `MetricExporter`，把 Span 与
Metric point 批量写入 JDBC 数据库。它只负责写入，不提供查询 API、实体 ORM、管理页面或数据保留任务。

```text
Agents-Flex instrumentation
        │
        ▼
BatchSpanProcessor / PeriodicMetricReader
        │
        ▼
JdbcSpanExporter / JdbcMetricExporter
        │ 应用提供 DataSource
        ▼
MySQL / PostgreSQL / 其他 JDBC 数据库
```

模块不包含数据库驱动或连接池，也不会关闭 `DataSource`。应用可以复用已有 HikariCP、Druid 或容器管理的
连接池，数据库连接、凭证轮换和连接池生命周期仍由应用负责。

## 添加 Maven 依赖

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-observability-jdbc</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

应用还需要添加自己的 JDBC driver。例如 MySQL 使用 MySQL Connector/J；该依赖不由
`agents-flex-observability-jdbc` 传递引入。

## 创建表

模块 jar 根目录包含 `mysql-schema.sql`，默认创建：

| 表 | 粒度 | 主要内容 |
| --- | --- | --- |
| `agents_flex_otel_spans` | 每个 Span 一行 | Trace 层级、状态、耗时、关联 ID、属性、事件、links、resource |
| `agents_flex_otel_metrics` | 每个 Metric point 一行 | Metric 元数据、时间、数值、聚合信息、属性、resource |

建议把脚本纳入 Flyway、Liquibase 或应用已有的数据库迁移流程。Exporter 不会在启动时自动执行 DDL，
避免运行账号需要建表权限，也避免框架绕过应用的 schema 版本管理。

### Span 表

常用关系字段被拆成独立列：

| 列 | 来源 | 用途 |
| --- | --- | --- |
| `trace_id`、`span_id`、`parent_span_id` | OTel Span Context | 重建调用树 |
| `start_epoch_nanos`、`duration_nanos` | Span 时间 | 时间范围和耗时查询 |
| `status_code` | OTel Status | 错误筛选 |
| `service_name` | Resource `service.name` | 服务维度 |
| `conversation_id` | `gen_ai.conversation.id` | 会话维度 |
| `account_id` | `enduser.id` | 账号维度 |
| `attributes_json` | Span attributes | 自定义业务属性 |
| `events_json`、`links_json` | Span events / links | 异常事件与跨链路关系 |
| `resource_attributes_json` | Resource attributes | 实例、环境和服务元数据 |

默认脚本在 trace、conversation、account、service、span name 和时间字段上建立索引，并用
`(trace_id, span_id)` 唯一约束避免同一个 Span 被重复写入。

### Metric 表

Counter 和 Gauge 的值进入 `value_long` 或 `value_double`。Histogram、Exponential Histogram 和 Summary
的 count、sum、min、max 使用独立列，桶和分位数保存在 `data_json`。

JDBC Metric Exporter 请求 cumulative temporality，因此同一 Metric 时间序列会按导出周期持续产生 point。
用户系统应使用 `metric_name`、`epoch_nanos` 和 `attributes_json` 识别时间序列，而不是把每行当成独立计数
增量。

## 配置 Exporter

在首次创建 ChatModel、ToolExecutor 或使用 `AgentsFlexHttpClient` 之前执行：

```java
import com.agentsflex.core.observability.Observability;
import com.agentsflex.observability.jdbc.JdbcTelemetryExporters;

System.setProperty("agentsflex.otel.service.name", "order-agent");

JdbcTelemetryExporters exporters = JdbcTelemetryExporters.builder(dataSource).build();

Observability.setCustomExporters(
    exporters.getSpanExporter(),
    exporters.getMetricExporter()
);
```

`setCustomExporters(...)` 会让 Agents-Flex 创建私有 SDK：

- Span 使用 `BatchSpanProcessor`；
- 默认最大队列为 4096，最大导出 batch 为 512；
- Metric 使用 `PeriodicMetricReader`，默认每 60 秒导出；
- JVM shutdown hook 会关闭框架创建的 Provider；
- `DataSource` 不属于框架，shutdown 时不会关闭连接池。

修改 Metric 周期：

```bash
-Dagentsflex.otel.metric.export.interval=30
```

## 自定义表名

```java
JdbcTelemetryExporters exporters = JdbcTelemetryExporters.builder(dataSource)
    .spanTable("observability.app_spans")
    .metricTable("observability.app_metrics")
    .build();
```

表名允许字母、数字、下划线、点和 `$`。其他字符会在 builder 阶段抛出 `IllegalArgumentException`，避免
表名配置进入动态 SQL 后形成注入风险。

修改表名只改变 INSERT 目标，不会创建表或改变列名。自定义表必须提供与参考 schema 一致的列。

## 关联业务请求

模型调用可直接设置 conversation 和 account：

```java
ChatOptions options = new ChatOptions();
options.setContextConversationId("conversation-42");
options.setContextAccountId("account-7");

chatModel.chat(prompt, options);
```

Chat Span 会设置：

| ChatOptions | Span attribute | JDBC 列 |
| --- | --- | --- |
| `contextConversationId` | `gen_ai.conversation.id` | `conversation_id` |
| `contextAccountId` | `enduser.id` | `account_id` |

额外业务字段可以在自定义 interceptor 中写入 Span：

```java
Span.current().setAttribute("app.tenant.id", tenantId);
Span.current().setAttribute("app.workflow.id", workflowId);
```

它们会保存在 `attributes_json`。如果某个字段是主要查询条件，应由应用维护自己的 migration，将其拆成
独立列或生成列并建立索引；JDBC exporter 不推断任意业务属性的索引策略。

## 事务与失败语义

每次 OTel `export(Collection<...>)` 使用一个连接和一个数据库事务：

1. 为 batch 创建 `PreparedStatement`；
2. 逐条绑定参数并调用 `addBatch()`；
3. `executeBatch()` 成功后提交；
4. 任意 `SQLException` 触发整批 rollback；
5. Exporter 通过 `CompletableResultCode` 把成功或失败返回给 OpenTelemetry，并记录失败日志。

`BatchSpanProcessor` 提供内存队列和批量调度，但不提供数据库故障后的持久化重试，也不是磁盘队列。
进程崩溃、队列溢出或数据库长时间不可用时，观测数据可能丢失。

如果业务要求观测数据具备更强的送达保证，推荐把 OTLP 发送到独立 Collector，由 Collector 承担缓冲、
重试和后端写入；或者在应用与数据库之间使用具备持久化能力的消息队列。不要让业务请求同步等待远程查询
系统完成处理。

## 适配其他 JDBC 数据库

INSERT 使用标准 `PreparedStatement`，不依赖 MySQL driver API。适配 PostgreSQL、MariaDB、Oracle 或
其他数据库时，需要根据目标数据库修改 DDL 类型与自增语法，但应保持 exporter 使用的列名和兼容类型。

需要注意：

- epoch nanos 使用 Java `long`，数据库列应能保存有符号 64 位整数；
- JSON 字段通过 `setString(...)` 写入，可以使用 TEXT/CLOB，也可以按数据库规则改成 JSON 类型；
- boolean、double 和 bigint 的实际类型由目标数据库映射；
- schema-qualified 表名可以通过 builder 配置；
- 数据分区、TTL、归档和索引由应用的 migration 管理。

## 内容与容量

- `agentsflex.otel.capture.content=false` 时不会保存模型响应、工具参数和工具结果；
- 开启内容采集后，相关内容进入 `attributes_json`，仍然受截断和脱敏规则约束；
- 高频 Metrics 会按导出周期持续写入，应预估行数并配置分区或保留策略；
- Span events 和 links 使用 JSON 保存，异常堆栈可能显著增加单行大小；
- 数据库账号只需要目标表的 INSERT 权限，建表与迁移应使用独立账号执行。

## 相关文档

- [Observability 快速开始](./getting-started)
- [模型与 HTTP 可观测](./model)
- [工具调用可观测](./tool)
- [故障排查与生产建议](./troubleshooting)

</div>
