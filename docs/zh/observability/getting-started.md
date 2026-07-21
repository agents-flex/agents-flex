<div v-pre>

# Observability 快速开始

## 这篇教程会完成什么

本教程先不要求部署 Jaeger、Tempo、Collector 或商业 APM。你将使用 Logging Exporter：

1. 让 Agents-Flex 自动记录一次模型调用；
2. 在应用日志中看到 Chat Span 和 HTTP Span；
3. 理解为什么 Metrics 不会在请求结束后立刻出现；
4. 确认接入正确后，再选择 OTLP 或 JDBC 作为正式数据出口。

如果 `Trace`、`Span`、`Metric` 或 `Exporter` 还是陌生词，请先阅读
[零基础理解可观测](./concepts)。

## 前置条件

- 项目已经使用 `agents-flex-core` 和任意 ChatModel 实现；
- ChatModel 能正常调用，先排除 API Key、模型地址等业务配置问题；
- 应用中存在一个可用的 SLF4J 日志实现，否则 Logging Exporter 没有地方输出；
- 不需要提前安装数据库或 APM。

自动埋点代码已经包含在 `agents-flex-core` 中。如果项目尚未直接声明 core，可添加：

```xml
<dependency>
    <groupId>com.agentsflex</groupId>
    <artifactId>agents-flex-core</artifactId>
    <version>${agents-flex.version}</version>
</dependency>
```

## 选择一种接入方式

自动埋点已经包含在 `agents-flex-core` 中，不需要手动注册 Chat 或 Tool 可观测拦截器。开始前只需选择
数据导出方式：

| 目标 | 推荐方式 |
| --- | --- |
| 本地确认 Span 和 Metrics | Logging Exporter |
| 发送到 Collector、Jaeger、Tempo、APM 平台 | OTLP Exporter |
| 复用 Java Agent、Spring 或应用已有 SDK | 默认全局 OpenTelemetry |
| 写入 MySQL 等数据库 | JDBC Exporter |
| 每次执行选择不同后端，或同时发送到多个后端 | Telemetry Route |

刚接触可观测时，先选 Logging；确认数据结构后再选正式后端。所有初始化配置都应在创建 ChatModel、
ToolExecutor 或首次使用 `AgentsFlexHttpClient` 之前完成。

## 第一步：把 Span 输出到日志

使用 JVM 参数启动应用：

```bash
java \
  -Dagentsflex.otel.enabled=true \
  -Dagentsflex.otel.exporter.type=logging \
  -Dagentsflex.otel.service.name=my-ai-service \
  -jar app.jar
```

这些参数的含义：

| 参数 | 作用 |
| --- | --- |
| `agentsflex.otel.enabled=true` | 打开 Agents-Flex 自动埋点 |
| `agentsflex.otel.exporter.type=logging` | 创建本地 SDK，并把遥测数据输出到日志 |
| `agentsflex.otel.service.name=my-ai-service` | 标识是哪一个服务产生的数据 |

## 第二步：执行一次模型调用

正常创建并调用 ChatModel，不需要手工创建 Span：

```java
OpenAIChatConfig config = new OpenAIChatConfig();
config.setApiKey(System.getenv("OPENAI_API_KEY"));
config.setModel("gpt-4.1-mini");
config.setObservabilityEnabled(true);

OpenAIChatModel chatModel = new OpenAIChatModel(config);
AiMessageResponse response = chatModel.chat(prompt, new ChatOptions());
```

`observabilityEnabled` 默认是 `true`，这里显式设置是为了让示例含义更清楚。

## 第三步：检查输出

不同 OpenTelemetry 版本的日志格式可能不同，不要依赖完整文本完全一致。重点确认存在以下信息：

```text
Span name: openai.chat
TraceId: 4bf92f3577b34da6a3ce929d0e0e4736
SpanId: 00f067aa0ba902b7
Attributes:
  gen_ai.provider.name = openai
  gen_ai.request.model = gpt-4.1-mini
  gen_ai.operation.name = chat
```

模型实现使用 `AgentsFlexHttpClient` 时，还应看到一个 `http.client.request` Span。它与 Chat Span 拥有相同
`trace_id`，并且 `parent_span_id` 指向 Chat Span：

```text
openai.chat
└── http.client.request
```

这说明 Trace Context 和父子调用关系已经生效。

如果完全没有日志，先检查：

1. JVM 参数是否真的传给运行中的 Java 进程；
2. 应用是否只有 `slf4j-api`，却没有 Logback、Log4j2 等实现；
3. ChatModel 的 `observabilityEnabled` 是否被设置成 `false`；
4. 配置是否在第一次模型或 HTTP 调用后才设置；
5. 日志级别是否过滤了 OTel Logging Exporter。

## 第四步：等待 Metrics

Span 在调用结束后即可导出，但 Metrics 默认每 60 秒由 `PeriodicMetricReader` 汇总一次。短命令行程序立即
退出时，可能只能看到 Span。

本地调试可以临时缩短周期：

```bash
-Dagentsflex.otel.metric.export.interval=5
```

等待至少 5 秒后，应看到 `agentsflex.gen_ai.request.count`、`gen_ai.client.operation.duration` 和 HTTP 指标。
理解两者区别：

- Span 是“刚才这一条请求发生了什么”；
- Metric 是“这段时间总体发生了多少次、通常多慢”。

## 第五步：制造一次可识别的错误

可以使用测试环境中的错误模型地址或让测试工具主动抛出异常。对应 Span 应出现：

- status 为 `ERROR`；
- exception event 或错误描述；
- `agentsflex.gen_ai.request.error.count` 或 `agentsflex.gen_ai.tool.call.error.count` 增加。

不要在生产环境为了验证而故意制造错误。

## 第六步：选择正式后端

本地日志只适合确认埋点，不适合跨实例查询和长期保存：

| 你的现状 | 下一步 |
| --- | --- |
| 团队已有 Collector/APM | 使用下面的 OTLP 配置 |
| 只有 MySQL，希望自己的系统查询 | 使用 JDBC Exporter |
| 应用已有 Java Agent 或 Spring OTel | 复用全局 OpenTelemetry |
| 不同业务执行需要不同后端 | 使用 Telemetry Route |
| 同一数据需要进入多个后端 | 为 Route 添加多个 destination |

## 发送到 OTLP Collector

Agents-Flex 使用 OpenTelemetry Java OTLP gRPC Exporter。Exporter endpoint、headers、timeout 等连接参数
遵循 OpenTelemetry Java 的标准环境变量或系统属性。

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4317

java \
  -Dagentsflex.otel.enabled=true \
  -Dagentsflex.otel.exporter.type=otlp \
  -Dagentsflex.otel.service.name=my-ai-service \
  -jar app.jar
```

也可以使用 Java 系统属性：

```bash
-Dotel.exporter.otlp.endpoint=http://127.0.0.1:4317
```

OTLP Span 使用 `BatchSpanProcessor`，Metrics 使用 `PeriodicMetricReader`。Collector 后面可以连接 Jaeger、
Tempo、Prometheus-compatible 后端或厂商 APM；这些后端由 Collector 或应用 OTel 配置管理，不由
Agents-Flex 直接配置。

接入后至少验证：

- APM 中能按 `service.name=my-ai-service` 找到服务；
- Chat 和 HTTP Span 位于同一 Trace；
- 错误 Span 能按 status 筛选；
- Metric 在一个导出周期后出现；
- Collector 不持续报告拒绝、认证失败或后端超时。

## 复用应用已有 OpenTelemetry

如果 Java Agent、Spring 或应用启动代码已经注册了全局 OpenTelemetry，保持默认配置即可：

```properties
agentsflex.otel.enabled=true
agentsflex.otel.exporter.type=none
```

Agents-Flex 只从 `GlobalOpenTelemetry` 获取 instrumentation scope，不注册或关闭宿主 Provider。

如果不希望使用全局实例，也可以显式注入：

```java
OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
    .setTracerProvider(tracerProvider)
    .setMeterProvider(meterProvider)
    .setPropagators(contextPropagators)
    .build();

Observability.setOpenTelemetry(openTelemetry);
```

此时 SDK、Provider、Processor 和 Exporter 都由应用管理。应用关闭时也应由应用执行 flush 和 shutdown，
`Observability.shutdown()` 不会关闭注入的实例。

## 写入 JDBC 数据库

添加 `agents-flex-observability-jdbc`，执行模块中的 `mysql-schema.sql`，然后注入 exporter：

```java
JdbcTelemetryExporters exporters = JdbcTelemetryExporters.builder(dataSource).build();

Observability.setCustomExporters(
    exporters.getSpanExporter(),
    exporters.getMetricExporter()
);
```

完整表结构、业务关联字段和事务语义见 [JDBC 持久化](./jdbc)。

如果宿主系统需要让自己的业务对象绑定一个或多个 APM 后端，不要为每个对象修改全局 SDK。应用可以保存
`telemetryRouteId`，在执行入口通过 `Observability.useRuntime(...)` 选择路由。完整配置见
[按执行上下文路由](./runtime-routing)。这个机制不依赖 `ReActAgent`、`IAgent` 或任何 Agents-Flex Agent 类型。

## 常用配置

| 系统属性 | 默认值 | 说明 | 读取时机 |
| --- | --- | --- | --- |
| `agentsflex.otel.enabled` | `true` | 全局开关 | 每次调用 |
| `agentsflex.otel.exporter.type` | `none` | `none`、`logging` 或 `otlp` | SDK 首次初始化 |
| `agentsflex.otel.service.name` | `agents-flex` | 内建 SDK 的 `service.name` | SDK 首次初始化 |
| `agentsflex.otel.metric.export.interval` | `60` | Metrics 导出周期，单位秒 | SDK 首次初始化 |
| `agentsflex.otel.capture.content` | `false` | 是否记录模型响应和工具内容 | 每次调用 |
| `agentsflex.otel.tool.excluded` | 空 | 不采集的工具名，逗号分隔 | 每次工具调用 |

全局开关可以在运行时读取新值，但 exporter 类型、service name 和导出周期属于 SDK 初始化配置，运行中修改
不会重建 Provider。

## 关闭与刷新

使用 `logging`、`otlp` 或 `setCustomExporters(...)` 时，SDK 由 Agents-Flex 创建，并注册 JVM shutdown
hook。应用也可以在自身生命周期回调中显式关闭：

```java
Observability.shutdown();
```

显式关闭只影响 Agents-Flex 创建的 Provider。关闭后不要继续发起需要观测的调用。

## 快速验收清单

完成接入后，用下面的清单确认不是“只有配置，没有数据”：

- [ ] 正常模型请求产生一个 Chat Span；
- [ ] 模型 HTTP 请求成为 Chat Span 的子节点；
- [ ] 错误请求的 Span status 为 `ERROR`；
- [ ] 等待导出周期后能看到请求数和延迟 Metric；
- [ ] URL query、API Key 和工具密钥没有出现在遥测数据中；
- [ ] `service.name` 能区分不同应用；
- [ ] 应用关闭时由正确的所有者关闭 SDK 和 DataSource；
- [ ] Exporter 或后端不可用不会改变业务调用结果。

## 下一步

- [零基础理解可观测](./concepts)
- [典型问题应该看 Trace 还是 Metric](./scenarios)
- [模型与 HTTP Span、Metrics](./model)
- [按执行上下文路由到一个或多个后端](./runtime-routing)
- [工具调用 Span、Metrics 与脱敏](./tool)
- [将可观测数据写入 JDBC](./jdbc)
- [故障排查与生产建议](./troubleshooting)

</div>
