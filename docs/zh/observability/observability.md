# Observability 可观测
<div v-pre>


## 1. 核心概念

`Observability` 模块是 Agents-Flex 提供的 **统一可观测性入口**，基于 **OpenTelemetry (OTel)** 实现，支持：

* **Tracing（链路追踪）**
  通过 `Tracer` 收集服务调用链信息，用于分析请求流程、性能瓶颈和故障排查。

* **Metrics（指标收集）**
  通过 `Meter` 收集服务运行指标，如 QPS、延迟、内存使用等，可用于监控和告警。

* **Exporter（数据导出）**
  支持将 Trace 和 Metrics 数据导出到：

    * `Logging`（控制台输出，便于开发调试）
    * `OTLP`（OpenTelemetry Collector，支持多平台整合）
    * 或者自定义 `SpanExporter`/`MetricExporter`



## 2. 快速入门

### 2.1 获取全局 Tracer 和 Meter

```java
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.metrics.Meter;

public class App {
    public static void main(String[] args) {
        // 获取全局 Tracer
        Tracer tracer = Observability.getTracer();

        // 获取全局 Meter
        Meter meter = Observability.getMeter();

        // 示例：创建一个 Span
        var span = tracer.spanBuilder("sample-operation").startSpan();
        try {
            // 执行业务逻辑
        } finally {
            span.end();
        }
    }
}
```



## 3. 配置方式

### 3.1 系统属性控制

| 属性                                       | 说明                                     | 默认值       |
| - | -- |-----------|
| `agentsflex.otel.enabled`                | 是否启用 Observability                     | `true`    |
| `agentsflex.otel.tool.excluded`          | 排除特定工具收集可观测性数据，逗号分隔                    | 空         |
| `agentsflex.otel.exporter.type`          | Exporter 类型，可选 `none`、`logging`、`otlp` | `none` |
| `agentsflex.otel.metric.export.interval` | Metrics 导出间隔（秒）                        | `60`      |
| `agentsflex.otel.capture.content`        | 是否采集模型响应、工具参数和工具结果等内容             | `false`   |
| `agentsflex.otel.service.name`           | 框架自建 SDK 使用的服务名                       | `agents-flex` |

示例：

```bash
-Dagentsflex.otel.enabled=true
-Dagentsflex.otel.exporter.type=otlp
-Dagentsflex.otel.tool.excluded=heartbeat,debug
-Dagentsflex.otel.metric.export.interval=30
-Dagentsflex.otel.capture.content=false
```

### 3.2 自定义 Exporter

未配置 exporter 时，Agents-Flex 复用应用已经注册的全局 `OpenTelemetry`，不会替换宿主 SDK。
也可以在首次使用前通过 `Observability.setOpenTelemetry(openTelemetry)` 显式注入应用管理的实例。

如果希望由 Agents-Flex 管理自定义 Exporter，可以在首次使用前通过 `Observability.setCustomExporters` 注入：

```java
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;

OtlpGrpcSpanExporter spanExporter = OtlpGrpcSpanExporter.builder()
        .setEndpoint("http://collector.company.com:4317")
        .addHeader("Authorization", "Bearer <token>")
        .setTimeout(Duration.ofSeconds(10))
        .build();

OtlpGrpcMetricExporter metricExporter = OtlpGrpcMetricExporter.builder()
        .setEndpoint("http://collector.company.com:4317")
        .build();

// 注入自定义 Exporter
Observability.setCustomExporters(spanExporter, metricExporter);
```



## 4. 高级应用示例

### 4.1 使用 OTLP Exporter 发送数据到 Collector

```java
// 系统属性方式配置 OTLP
System.setProperty("agentsflex.otel.exporter.type", "otlp");
System.setProperty("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector.company.com:4317");

// 或者直接使用自定义 Exporter
Observability.setCustomExporters(
        OtlpGrpcSpanExporter.builder()
                .setEndpoint("http://collector.company.com:4317")
                .build(),
        OtlpGrpcMetricExporter.builder()
                .setEndpoint("http://collector.company.com:4317")
                .build()
);
```

### 4.2 排除特定工具

```java
System.setProperty("agentsflex.otel.tool.excluded", "heartbeat,debug");

if (Observability.isToolExcluded("heartbeat")) {
    System.out.println("Heartbeat 工具已被排除，不会收集指标");
}
```

### 4.3 调整 Metrics 导出间隔

```java
System.setProperty("agentsflex.otel.metric.export.interval", "15"); // 每15秒导出一次
```



## 5. 生命周期管理

* **宿主管理**：默认复用全局 OTel，Agents-Flex 不负责关闭宿主 Provider。
* **框架管理**：显式配置 exporter 时创建私有 SDK，并在 JVM 关闭时释放；也可调用 `Observability.shutdown()`。



## 6. 注意事项

1. 如果 `agentsflex.otel.enabled=false`，所有可观测性操作将被禁用且不会触发 SDK 初始化。
2. 使用 OTLP Exporter 时，请确保 Collector 可访问。
3. OTLP 和自定义 Span Exporter 使用 **BatchSpanProcessor**；Logging 使用同步处理器。
4. 自定义 Exporter 或 OpenTelemetry 实例必须在首次获取 Tracer/Meter 前注入。
5. 内容采集默认关闭；开启后工具 JSON 会递归脱敏，但仍应避免上报不必要的业务数据。


## 7. 总结

`Observability` 提供了：

* **全局 Tracer / Meter**
* **可扩展 Exporter 支持**
* **OTLP 与 Logging 支持**
* **灵活的系统属性和自定义配置**

适合在 **生产和开发环境**统一收集链路追踪和指标数据，方便与 Prometheus、Jaeger、Grafana、Datadog 等平台整合。



</div>
