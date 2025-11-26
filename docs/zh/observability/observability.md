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
| `agentsflex.otel.exporter.type`          | Exporter 类型，可选 `logging`、`otlp` 或自定义类名 | `logging` |
| `agentsflex.otel.metric.export.interval` | Metrics 导出间隔（秒）                        | `60`      |

示例：

```bash
-Dagentsflex.otel.enabled=true
-Dagentsflex.otel.exporter.type=otlp
-Dagentsflex.otel.tool.excluded=heartbeat,debug
-Dagentsflex.otel.metric.export.interval=30
```

### 3.2 自定义 Exporter

如果希望使用自定义的 OTLP Exporter 或其他第三方 Exporter，可以通过 `Observability.setCustomExporters` 注入：

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

* **自动初始化**：首次调用 `getTracer()` 或 `getMeter()` 时自动初始化。
* **Shutdown Hook**：JVM 关闭时自动调用 `shutdown()`，保证 TracerProvider 和 MeterProvider 安全关闭。



## 6. 注意事项

1. 如果 `agentsflex.otel.enabled=false`，所有可观测性操作将被禁用。
2. 使用 OTLP Exporter 时，请确保 Collector 可访问。
3. 默认情况下，Tracing 使用 **BatchSpanProcessor**，Metrics 使用 **PeriodicMetricReader**。
4. 自定义 Exporter 注入后，`agentsflex.otel.exporter.type` 配置会被忽略。


## 7. 总结

`Observability` 提供了：

* **全局 Tracer / Meter**
* **可扩展 Exporter 支持**
* **OTLP 与 Logging 支持**
* **灵活的系统属性和自定义配置**

适合在 **生产和开发环境**统一收集链路追踪和指标数据，方便与 Prometheus、Jaeger、Grafana、Datadog 等平台整合。



</div>
