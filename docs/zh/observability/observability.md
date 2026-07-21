<div v-pre>

# Observability 模块概述

## 这是什么

当系统只有一次模型调用时，打印开始时间、结束时间和异常可能就够了。但实际 AI 应用通常还会调用工具、
访问知识库、请求多个模型服务，并使用流式回调或线程池。用户只会说“回答很慢”或“刚才失败了”，开发者
却需要判断问题发生在哪一步。

Agents-Flex 的 Observability 基于 [OpenTelemetry](https://opentelemetry.io/)，自动为模型请求、工具调用和
框架 HTTP 请求留下标准化运行线索。你可以用这些数据还原一次请求的调用过程，也可以观察一段时间内的
请求量、错误率和延迟趋势。

如果你此前没有接触过可观测，建议先阅读[零基础理解可观测](./concepts)。其中会解释 Trace、Span、Metric、
Context 和 Exporter，不要求 OpenTelemetry 基础。

## 它能帮你回答什么

典型用途包括：

- “这一次请求为什么用了 12 秒？”：查看 Trace 中每个 Chat、Tool 和 HTTP Span 的耗时；
- “最近模型是不是整体变慢了？”：查看按 model/provider 聚合的延迟 Metric；
- “工具为什么偶发失败？”：查看 Tool Span 的异常和子 HTTP Span 状态码；
- “客服只有会话 ID，如何找到当时的调用？”：通过 conversation ID 查询 Span 和 trace ID；
- “不同业务对象如何进入不同 APM？”：在执行入口选择 Telemetry Route；
- “APM 和 MySQL 都需要数据怎么办？”：为一个 Route 配置多个独立 destination。

完整的问题、接入方式和预期结论见[典型场景与实践](./scenarios)。

## 使用前后有什么区别

未接入时，开发者通常需要：

1. 从大量日志中猜测哪些记录属于同一次请求；
2. 分别计算模型、工具和 HTTP 的耗时；
3. 尝试在本地复现生产环境的偶发错误；
4. 临时增加日志后重新发布，再等待问题出现。

接入后，一次请求可以直接呈现为调用树：

```text
业务请求
├── openai.chat                 2.1s
│   └── http.client.request     2.0s  200
└── tool.getWeather             8.4s  ERROR
    └── http.client.request     8.3s  504
```

同时，Metric 可以告诉你这是一个偶发问题，还是最近所有天气工具都在变慢。

## Agents-Flex 做什么，不做什么

Agents-Flex 负责：

- 自动创建 Chat、Tool 和 HTTP Span；
- 记录请求数、错误数和耗时 Metric；
- 维护父子调用关系并传播 Trace Context；
- 把数据交给 Logging、OTLP、JDBC 或用户自定义 Exporter；
- 支持按一次执行选择后端，并向多个后端扇出。

Agents-Flex 不负责：

- 提供 Trace 查询页面、Dashboard、告警或报表 UI；
- 管理 APM、Collector、数据库和连接池；
- 决定用户系统中的数据访问权限和保留周期；
- 保证遥测数据像业务消息一样永不丢失；
- 自动理解宿主系统中的“智能体”等业务对象。

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

对于初学者，可以先把 Exporter 理解成“数据出口”：

| 出口 | 最适合的阶段 | 是否提供查询页面 |
| --- | --- | --- |
| Logging | 第一次接入、本地验证 | 否，只输出日志 |
| OTLP | 生产环境发送到 Collector/APM | 由后端提供 |
| JDBC | 写入 MySQL 等数据库，由自己的系统查询 | 由用户系统提供 |
| 自定义 Exporter | 已有内部遥测平台 | 取决于内部平台 |

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

## 最小接入路线

第一次使用不需要先部署 APM：

1. 设置 `agentsflex.otel.exporter.type=logging`；
2. 正常执行一次 ChatModel；
3. 在日志中找到 Chat Span 和它的 HTTP 子 Span；
4. 等待 Metric 导出周期，观察请求计数和耗时；
5. 确认数据符合预期后，再切换到 OTLP 或 JDBC。

完整命令和验证方法见[快速开始](./getting-started)。

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

- [零基础理解 Trace、Span、Metric 和 Context](./concepts)
- [用真实问题理解典型场景](./scenarios)
- [快速开始](./getting-started)
- [按执行上下文路由到一个或多个后端](./runtime-routing)
- [模型与 HTTP 可观测](./model)
- [工具调用可观测](./tool)
- [JDBC 持久化](./jdbc)
- [故障排查与生产建议](./troubleshooting)

</div>
