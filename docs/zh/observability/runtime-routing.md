<div v-pre>

# 按执行上下文路由可观测数据

## 先判断是否真的需要 Route

大多数只有一个遥测后端的应用不需要 Route，使用全局 Logging、OTLP 或 `setCustomExporters(...)` 即可。

| 需求 | 推荐方式 |
| --- | --- |
| 整个应用都发送到同一个 APM | 全局 OTLP 或宿主 OpenTelemetry SDK |
| 整个应用都写入同一个数据库 | 全局 `setCustomExporters(...)` |
| 每次业务执行可能选择不同后端 | `TelemetryRoute` |
| 同一次执行要同时发送到多个后端 | 一个 Route 配置多个 destination |
| 后端很多且需要可靠重试、磁盘缓冲 | OTLP 到 Collector，由 Collector fan-out |

初学者可以把 Route 理解为“本次执行的可观测数据出口组合”，把 destination 理解为“组合中的一个具体
出口”。

## 适用场景

宿主系统可能有自己的“智能体”或其他业务对象，每个对象绑定不同的 APM 配置，一次执行还可能需要同时把
数据发送到多个后端。这里的“智能体”只是宿主系统的业务概念，不对应 Agents-Flex 的 `ReActAgent`、
`IAgent` 或其他框架类型。

Agents-Flex 不保存业务对象与 APM 的关系，也不修改 ChatModel 或 Tool 的配置模型。宿主系统只需在业务对象
中保存一个 `telemetryRouteId`，并在调用 ChatModel、Tool 或 `AgentsFlexHttpClient` 的执行边界选择对应路由。

```text
宿主业务对象 --保存--> telemetryRouteId
                         │
                         ▼
                TelemetryRouteRegistry
                         │ 解析
                         ▼
                  TelemetryRoute
                 ┌───────┴────────┐
                 ▼                ▼
          OTLP destination   JDBC destination
          独立 Span 队列      独立 Span 队列
          独立 Metric reader  独立 Metric reader
```

## 核心对象

| 类型 | 职责 |
| --- | --- |
| `TelemetryDestination` | 描述一个后端的 Span Exporter、Metric Exporter 和独立导出参数 |
| `TelemetryRoute` | 一组可同时接收数据的 destination，也是一次执行可选择的 OpenTelemetry runtime |
| `TelemetryRouteRegistry` | 由应用持有，根据持久化的 route ID 查找路由并统一关闭 |
| `Observability.useRuntime(...)` | 把路由和本次执行的固定 Span 属性绑定到当前 OTel `Context` |

一个 route 只做一次采样决定，并让所有 destination 共享同一个 Resource、Trace ID 和 Span ID。每个 Span
destination 使用自己的 `BatchSpanProcessor`，每个 Metric destination 使用自己的
`PeriodicMetricReader`。一个后端变慢时，不会占用另一个后端的 Span 导出队列。

## 从单后端 Route 开始理解

下面创建一个只发送 Span 到 APM 的最小 Route：

```java
SpanExporter apmExporter = OtlpGrpcSpanExporter.builder()
    .setEndpoint("http://apm-collector:4317")
    .build();

TelemetryRoute apmRoute = TelemetryRoute.builder("company-apm-route")
    .serviceName("customer-service")
    .addDestination(TelemetryDestination.builder("company-apm")
        .spanExporter(apmExporter)
        .spanProcessingMode(SpanProcessingMode.BATCH)
        .build())
    .build();
```

这里的两个 ID 用途不同：

- `company-apm-route`：宿主业务对象保存并在执行时选择的 route ID；
- `company-apm`：Route 内部的 destination ID，用于识别配置并防止重复。

只配置 Span Exporter 是允许的，此时该 destination 不接收 Metrics。反过来也可以只配置 Metric Exporter。

## 创建多后端路由

下面的路由同时发送到 OTLP APM 和 MySQL。`DataSource`、数据库表结构及 JDBC 依赖配置参见
[JDBC 持久化](./jdbc)。

```java
JdbcTelemetryExporters jdbc = JdbcTelemetryExporters.builder(dataSource).build();

SpanExporter apmSpans = OtlpGrpcSpanExporter.builder()
    .setEndpoint("http://apm-collector:4317")
    .build();
MetricExporter apmMetrics = OtlpGrpcMetricExporter.builder()
    .setEndpoint("http://apm-collector:4317")
    .build();

TelemetryRoute orderRoute = TelemetryRoute.builder("order-observability")
    .serviceName("order-assistant")
    .resourceAttributes(Attributes.of(
        AttributeKey.stringKey("deployment.environment.name"), "production"))
    .addDestination(TelemetryDestination.builder("company-apm")
        .spanExporter(apmSpans)
        .metricExporter(apmMetrics)
        .spanProcessingMode(SpanProcessingMode.BATCH)
        .spanMaxQueueSize(4096)
        .spanMaxExportBatchSize(512)
        .metricExportInterval(Duration.ofSeconds(30))
        .build())
    .addDestination(TelemetryDestination.builder("audit-mysql")
        .spanExporter(jdbc.getSpanExporter())
        .metricExporter(jdbc.getMetricExporter())
        .spanProcessingMode(SpanProcessingMode.BATCH)
        .spanMaxQueueSize(8192)
        .metricExportInterval(Duration.ofSeconds(60))
        .build())
    .build();

TelemetryRouteRegistry routes = new TelemetryRouteRegistry()
    .register(orderRoute);
```

Exporter 实例应只归属于一个 destination。`TelemetryRoute` 关闭时，OTel Provider 会关闭它注册的 Processor、
Reader 和 Exporter；`TelemetryRouteRegistry.close()` 会关闭其中所有 route，但不会关闭 JDBC `DataSource`。

### 为什么不使用一个 CompositeExporter

如果把两个后端串在同一个 Exporter 中，它们会共享同一批处理队列。MySQL 写入卡住时，后面的 APM 导出也
可能被拖慢。Route 为每个 destination 创建独立 Processor/Reader，因此可以分别设置队列容量、批大小、
超时和 Metric 周期。

独立队列并不意味着完全没有资源影响：所有队列仍在同一个 JVM 中，过多 Route 或过大的队列仍会占用内存
和调度线程。因此 Route 应在启动时复用，而不是每次请求创建。

## 在业务执行入口绑定

假设宿主系统自己的业务对象提供 ID、名称和 `telemetryRouteId`：

```java
TelemetryRoute route = routes.require(definition.getTelemetryRouteId());
Attributes executionAttributes = Attributes.builder()
    .put("app.subject.id", definition.getId())
    .put("app.subject.name", definition.getName())
    .build();

try (Scope ignored = Observability.useRuntime(route, executionAttributes)) {
    chatModel.chat(prompt, options);
}
```

Scope 内由 Agents-Flex 创建的 Chat、Tool 和 HTTP Span 都会使用该 route，并自动带上
`app.subject.id`、`app.subject.name`。固定执行属性只附加到 Span，不附加到内置 Metrics，避免业务对象 ID
造成高基数时间序列。

属性名由宿主系统定义。若查询系统需要按这些字段高频过滤，可以在 JDBC schema 中增加独立列或生成列及
索引；Agents-Flex 不推断业务字段的存储结构。

`routes.require(...)` 在 route 不存在时会抛出异常，适合把错误配置阻止在执行入口。如果业务希望无配置时
继续使用全局 OpenTelemetry，可以先调用 `routes.get(...)`，为空时不打开 runtime Scope。

### 嵌套 Scope

Scope 关闭后会恢复进入前的 Route，因此可以安全嵌套：

```java
try (Scope outer = Observability.useRuntime(routeA)) {
    chatModel.chat(promptA, options); // 发送到 routeA

    try (Scope inner = Observability.useRuntime(routeB)) {
        chatModel.chat(promptB, options); // 发送到 routeB
    }

    chatModel.chat(promptC, options); // 再次发送到 routeA
}
```

不要忘记 try-with-resources。Scope 未关闭会让同一线程后续请求错误地继承 Route。

## 异步与流式执行

`Scope` 必须在创建它的线程关闭。向线程池提交任务时，应捕获完整 OTel `Context`，其中同时包含 Trace、
route 和固定执行属性：

```java
Context captured = Context.current();
executor.execute(captured.wrap(() -> {
    chatModel.chat(prompt, options);
}));
```

也可以在任务中使用 `captured.makeCurrent()`，但不要把请求线程创建的 `Scope` 交给另一个线程关闭。
Agents-Flex 的流式 Chat 拦截器会为自身回调恢复完整 Context；回调中再次提交的自定义异步任务仍需由应用
传播 Context。

## 生命周期和生产建议

应用应在启动阶段构造并注册 route，在停止阶段关闭 registry：

```java
routes.close();
```

关闭后不能再注册或使用其中的 route。路由配置需要更新时，建议构造新的 registry 或新 route，在没有执行
继续引用旧 route 后再关闭旧实例，不要在请求处理中反复创建 SDK。

应用直连多个后端适合后端数量较少、配置稳定的场景。需要复杂路由、持久化重试、跨进程缓冲或频繁变更
后端时，优先只发送到 OpenTelemetry Collector，再由 Collector fan-out。无论采用哪种方式，可观测导出
失败都不应成为业务调用的成功条件。

## 场景：APM 正常，但 MySQL 临时不可用

一个 Route 同时配置 APM 和 MySQL destination 时：

1. 同一个 Span 结束后分别进入两个 BatchSpanProcessor；
2. APM 队列可以继续正常导出；
3. JDBC Exporter 记录失败并把结果返回 OTel；
4. 可观测失败不会让已经成功的模型调用变成业务失败；
5. JDBC 的内存队列不是持久化消息队列，数据库长时间不可用时数据可能丢失。

如果 MySQL 数据必须可靠送达，不应让业务请求同步等待数据库；应考虑 Collector、持久化队列或专门的数据
管道。

## 相关文档

- [Observability 概述](./observability)
- [典型场景与实践](./scenarios)
- [Observability 快速开始](./getting-started)
- [JDBC 持久化](./jdbc)
- [故障排查与生产建议](./troubleshooting)

</div>
