<div v-pre>

# Observability 模块概述

## Observability 解决什么问题

Agents-Flex 的 Observability 基于 [OpenTelemetry](https://opentelemetry.io/)，用于记录模型请求、工具调用和
框架 HTTP 请求的调用链与运行指标。它负责创建 Span、记录 Metrics、传播 Trace Context，并把数据交给
OpenTelemetry Exporter；它不提供 Trace 查询页面、告警系统或报表 UI。

典型用途包括：

- 查看一次业务请求经过了哪些模型、HTTP 请求和工具调用；
- 按 provider、model、tool 或 HTTP host 统计调用量、错误率和耗时；
- 通过 conversation、account 或自定义属性关联业务请求；
- 把数据导出到 OpenTelemetry Collector，或直接写入 JDBC 数据库；
- 在不记录 Prompt、模型响应和工具参数的前提下完成基础运行监控。

Observability 是横切能力。模型、工具和 HTTP 客户端不依赖某一个具体后端，查询系统也不需要进入
Agents-Flex 的调用链。

## 模块组成

```text
agents-flex-core
├── Observability                       SDK 选择、全局开关和生命周期
├── TelemetryRoute / Destination        按执行上下文选择一个或多个后端
├── ChatObservabilityInterceptor        同步与流式模型调用
├── ToolObservabilityInterceptor        ToolExecutor 工具调用
└── AgentsFlexHttpClient                HTTP client Span、Metrics 和上下文传播

agents-flex-observability
└── agents-flex-observability-jdbc
    ├── JdbcTelemetryExporters          JDBC SpanExporter / MetricExporter 入口
    └── mysql-schema.sql                MySQL 参考表结构
```

| Maven 模块 | 用途 | 是否依赖数据库驱动 |
| --- | --- | --- |
| `agents-flex-core` | 自动埋点、OTel SDK、Logging 与 OTLP Exporter | 否 |
| `agents-flex-observability-jdbc` | 通过应用提供的 `DataSource` 批量写入 Span 和 Metric point | 否 |

JDBC 模块不绑定 MySQL Connector/J、HikariCP 或 Druid。数据库驱动、连接池和 `DataSource` 生命周期由
应用管理。

## 数据流

```text
ChatModel / ToolExecutor / AgentsFlexHttpClient
                    │
                    │ 创建 Span、记录 Metrics
                    ▼
            OpenTelemetry API
                    │
        ┌───────────┴───────────┐
        │                       │
应用管理的 OpenTelemetry    Agents-Flex 私有 SDK
        │                       │
 Java Agent / Spring /       Logging / OTLP /
 自定义 SDK                  自定义 Exporter
        └───────────┬───────────┘
                    ▼
 Collector / APM / JDBC / 其他存储
```

Agents-Flex 不会替换应用已经注册的全局 OpenTelemetry。没有显式配置 exporter 时，框架复用
`GlobalOpenTelemetry`；如果应用也没有注册 SDK，API 会表现为 no-op，不产生导出数据。

## OpenTelemetry 所有权

接入前应先确定谁负责创建和关闭 SDK。

| 模式 | 配置方式 | SDK 所有者 | 适用场景 |
| --- | --- | --- | --- |
| 复用全局实例 | 默认，`exporter.type=none` | 应用、Java Agent 或 Spring | 应用已有统一 OTel 配置 |
| Agents-Flex 内建 Exporter | `logging` 或 `otlp` | Agents-Flex | 独立应用、快速接入 |
| 显式注入 SDK | `Observability.setOpenTelemetry(...)` | 应用 | 需要自定义 Resource、Sampler、Processor |
| 显式注入 Exporter | `Observability.setCustomExporters(...)` | Agents-Flex 管理 Provider | JDBC 或自定义持久化后端 |
| 执行级路由 | `Observability.useRuntime(...)` | 应用通过 Registry 管理 Route | 不同业务对象选择不同后端，或同时发送到多个后端 |

`setOpenTelemetry(...)` 和 `setCustomExporters(...)` 必须在首次模型或 HTTP 调用之前执行。初始化完成后
再次设置会抛出 `IllegalStateException`，避免运行期间静默更换 SDK，造成一部分数据进入旧后端、另一部分
进入新后端。

## 自动埋点范围

| 调用面 | Trace | Metrics | 自动启用条件 |
| --- | --- | --- | --- |
| 同步 Chat | 一个模型 Span，内部 HTTP Span 为子节点 | 请求数、耗时、错误数 | 全局开关和模型开关均开启 |
| 流式 Chat | Span 持续到 `onStop` 或 `onFailure` | 请求数、完整流式耗时、错误数 | 全局开关和模型开关均开启 |
| Tool 调用 | 一个 `tool.{name}` Span | 调用数、耗时、错误数 | 全局开关开启且工具未排除 |
| `AgentsFlexHttpClient` | `CLIENT` Span，自动注入 Trace Context | 请求数、耗时、错误数 | 全局开关开启 |

框架内置拦截器位于责任链外层。用户自定义 Chat 或 Tool interceptor 执行时，当前 Span 已经可通过
`Span.current()` 获取，因此可以追加租户、任务类型等业务属性。

## 隐私默认值

可观测数据经常会进入独立平台或数据库，应把它视为另一个数据安全边界。

Agents-Flex 默认只记录运行元数据，不记录模型响应、工具参数和工具结果：

```properties
agentsflex.otel.capture.content=false
```

显式开启内容采集后：

- 模型响应最多记录 500 个字符；
- 工具参数按 JSON 解析，对 password、token、secret、apiKey、authorization、cookie、session 等字段递归脱敏；
- 无法解析的工具参数不会原样上报；
- 工具结果会先序列化、脱敏并限制长度；文件、流和二进制只记录类型占位符；
- HTTP URL 会移除 user-info、query 和 fragment。

脱敏不是数据授权。生产环境仍应只采集确有用途的数据，并在 Exporter、数据库和查询系统中设置访问控制、
保留周期和审计策略。

## 下一步

- [快速开始](./getting-started)
- [按执行上下文路由到一个或多个后端](./runtime-routing)
- [模型与 HTTP 可观测](./model)
- [工具调用可观测](./tool)
- [JDBC 持久化](./jdbc)
- [故障排查与生产建议](./troubleshooting)

</div>
