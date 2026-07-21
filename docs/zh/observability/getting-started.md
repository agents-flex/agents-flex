<div v-pre>

# Observability 快速开始

## 选择一种接入方式

自动埋点已经包含在 `agents-flex-core` 中，不需要手动注册 Chat 或 Tool 可观测拦截器。开始前只需选择
数据导出方式：

| 目标 | 推荐方式 |
| --- | --- |
| 本地确认 Span 和 Metrics | Logging Exporter |
| 发送到 Collector、Jaeger、Tempo、APM 平台 | OTLP Exporter |
| 复用 Java Agent、Spring 或应用已有 SDK | 默认全局 OpenTelemetry |
| 写入 MySQL 等数据库 | JDBC Exporter |

所有初始化配置都应在创建 ChatModel、ToolExecutor 或首次使用 `AgentsFlexHttpClient` 之前完成。

## 最短示例：输出到日志

启动参数：

```bash
java \
  -Dagentsflex.otel.enabled=true \
  -Dagentsflex.otel.exporter.type=logging \
  -Dagentsflex.otel.service.name=my-agent \
  -jar app.jar
```

之后正常调用模型即可：

```java
OpenAIChatConfig config = new OpenAIChatConfig();
config.setApiKey(System.getenv("OPENAI_API_KEY"));
config.setModel("gpt-4.1-mini");
config.setObservabilityEnabled(true);

OpenAIChatModel chatModel = new OpenAIChatModel(config);
AiMessageResponse response = chatModel.chat(prompt, new ChatOptions());
```

`observabilityEnabled` 默认是 `true`。Logging Span 在调用结束后输出；Metrics 默认每 60 秒导出一次，
因此程序立即退出时可能只能看到 Span。

## 发送到 OTLP Collector

Agents-Flex 使用 OpenTelemetry Java OTLP gRPC Exporter。Exporter endpoint、headers、timeout 等连接参数
遵循 OpenTelemetry Java 的标准环境变量或系统属性。

```bash
export OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4317

java \
  -Dagentsflex.otel.enabled=true \
  -Dagentsflex.otel.exporter.type=otlp \
  -Dagentsflex.otel.service.name=my-agent \
  -jar app.jar
```

也可以使用 Java 系统属性：

```bash
-Dotel.exporter.otlp.endpoint=http://127.0.0.1:4317
```

OTLP Span 使用 `BatchSpanProcessor`，Metrics 使用 `PeriodicMetricReader`。Collector 后面可以连接 Jaeger、
Tempo、Prometheus-compatible 后端或厂商 APM；这些后端由 Collector 或应用 OTel 配置管理，不由
Agents-Flex 直接配置。

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

## 下一步

- [模型与 HTTP Span、Metrics](./model)
- [工具调用 Span、Metrics 与脱敏](./tool)
- [故障排查与生产建议](./troubleshooting)

</div>
