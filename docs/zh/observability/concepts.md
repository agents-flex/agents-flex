<div v-pre>

# 零基础理解可观测

## 先用一句话理解

可观测（Observability）就是：系统运行时主动留下足够的线索，让你在不知道内部哪里出问题的情况下，仍能
通过这些线索回答“发生了什么、慢在哪里、为什么失败、影响了多少请求”。

它不是一个监控页面，也不是某家 APM 产品。Agents-Flex 负责在模型、工具和 HTTP 调用中产生标准的
OpenTelemetry 数据；日志系统、APM、OpenTelemetry Collector 或 MySQL 等后端负责接收和查询这些数据。

## 为什么只看应用日志不够

假设用户反馈：“刚才问天气等了 12 秒，最后还失败了。”普通日志可能只有：

```text
request failed
```

即使日志更完整，你仍要手工关联模型日志、工具日志和 HTTP 日志，而且多个用户并发时很容易混在一起。

可观测数据会给一次请求分配一个 `trace_id`，并把请求中的每一步组织成树：

```text
一次“杭州天气如何”的请求（Trace，12.3 秒）
├── qwen.chat（1.8 秒，成功）
│   └── http.client.request（1.7 秒，200）
├── tool.getWeather（10.1 秒，失败）
│   └── http.client.request（10.0 秒，504）
└── qwen.chat（0.4 秒，未执行或失败）
```

不需要先猜是模型慢、工具慢还是网络慢。调用树已经告诉你：天气工具调用的外部 HTTP 请求超时。

这棵完整调用树需要一个覆盖业务流程的父 Context。它通常由 Web 框架的 OpenTelemetry instrumentation、
Java Agent 或宿主业务代码创建。Agents-Flex 自动创建 Chat、Tool 和 HTTP Span，但不会自行猜测多次独立调用
是否属于同一个业务请求。

## 三类最重要的数据

### Trace：还原一次请求经历了什么

Trace 表示一条完整调用链。一次用户请求、一次后台任务或一次业务流程都可以是一条 Trace。

Trace 最适合回答：

- 这一个请求经过了哪些模型、工具和外部服务？
- 12 秒到底花在哪一步？
- HTTP 失败发生在哪个上游操作中？
- 同一个用户反馈对应哪条调用链？

### Span：调用链中的一个步骤

Span 是 Trace 中的一段操作。Agents-Flex 会自动创建三类核心 Span：

| Span 示例 | 表示什么 | 常见信息 |
| --- | --- | --- |
| `openai.chat` | 一次同步模型调用 | provider、model、token、成功状态、耗时 |
| `openai.chatStream` | 一次完整流式模型调用 | 从请求开始到流式结束的总耗时 |
| `tool.getWeather` | 一次工具调用 | 工具名、异常类型、可选的脱敏参数 |
| `http.client.request` | 一次框架 HTTP 请求 | 方法、目标 host、状态码、安全 URL |

Span 之间有父子关系。例如模型调用内部发出的 HTTP 请求会成为模型 Span 的子节点。

### Metric：观察一段时间内的总体趋势

Metric 是按时间聚合的数字，例如请求次数、错误次数和耗时分布。

Metric 最适合回答：

- 最近 10 分钟模型请求量是否突然上涨？
- 哪个模型整体最慢？
- 工具失败率是否超过告警阈值？
- 发布新版本后 P95 延迟是否恶化？

Agents-Flex 内置的常见 Metric：

| Metric | 含义 |
| --- | --- |
| `llm.request.count` | 模型请求总数 |
| `llm.request.latency` | 模型请求耗时分布 |
| `llm.request.error.count` | 模型失败请求数 |
| `tool.call.count` | 工具调用总数 |
| `tool.call.latency` | 工具调用耗时分布 |
| `http.client.request.duration` | HTTP 请求耗时分布 |

简单记忆：定位“某一次请求”通常看 Trace；判断“整个系统最近怎么样”通常看 Metric。

## 常见名词对照

| 名词 | 小白理解 | 在 Agents-Flex 中的作用 |
| --- | --- | --- |
| Attribute | 附在数据上的标签 | 保存 model、tool name、HTTP status 等查询条件 |
| Status | 这个步骤成功还是失败 | 错误响应和异常会把 Span 标记为 `ERROR` |
| Resource | 是谁产生了数据 | 通常包含 `service.name`、版本、部署环境 |
| Context | 当前请求随身携带的调用链上下文 | 维持父子 Span、Route 和执行属性 |
| Tracer | 创建 Span 的对象 | Chat、Tool 和 HTTP 埋点通过它创建调用步骤 |
| Meter | 创建 Metric 的对象 | 创建 Counter 和 Histogram |
| Exporter | 把数据送出去的适配器 | 发送到日志、OTLP、JDBC 或其他后端 |
| Processor | Span 进入 Exporter 前的处理器 | 可以同步发送，也可以在内存中批量发送 |
| Metric Reader | 定期读取并导出 Metric | 默认按周期汇总，而不是每次请求立即发送 |
| Collector | 独立的遥测中转服务 | 接收 OTLP，再缓冲、重试并转发到 APM |
| APM | 查询和展示可观测数据的平台 | 展示 Trace、Dashboard 和告警；不属于 Agents-Flex |

## 数据如何流动

```text
用户请求
   │
   ▼
ChatModel / ToolExecutor / AgentsFlexHttpClient
   │ 自动创建 Span、记录 Metric
   ▼
OpenTelemetry API + SDK
   │ Processor / Reader
   ▼
Exporter
   ├── Logging：本地验证
   ├── OTLP：发送到 Collector 或 APM
   └── JDBC：写入 MySQL 等数据库
```

Agents-Flex 的职责到 Exporter 为止。如何搜索、画图、配置告警和展示页面，取决于用户选择的后端。

## Attribute 和 Resource 有什么区别

这是初次接入最容易混淆的概念：

| 数据 | 应放哪里 | 原因 |
| --- | --- | --- |
| 服务名、服务版本、部署环境 | Resource | 同一 SDK 生命周期内基本不变 |
| model、provider、tool name、HTTP status | Span/Metric Attribute | 描述某类操作，可用于聚合 |
| conversation ID、account ID、业务对象 ID | Span Attribute | 每次请求变化，基数很高 |
| 完整 URL、Prompt、工具参数 | 默认不要采集 | 可能敏感且容易造成容量问题 |

不要把用户 ID、conversation ID 等放进 Metrics。每个不同组合都可能变成一条新的时间序列，最终导致 APM
费用、内存和查询压力快速上升。Agents-Flex 只把这类执行属性加入 Span。

## Context 为什么重要

Context 负责告诉新 Span：“你的父 Span 是谁，你现在属于哪条 Trace，你应该使用哪条遥测 Route。”

同步代码通常不需要手工处理。但任务切换到线程池时，如果没有传播 Context：

```text
预期：用户请求 -> Chat -> Tool -> HTTP
实际：用户请求 -> Chat      Tool -> HTTP（变成另一条 Trace）
```

跨线程时应捕获 Context：

```java
Context captured = Context.current();
executor.execute(captured.wrap(() -> runToolOrModel()));
```

Agents-Flex 会处理自身的流式 Chat 回调，但无法自动处理用户创建的所有线程池和异步框架。

### 谁负责创建最外层 Span

如果应用已经使用 OpenTelemetry Java Agent 或 Web 框架 instrumentation，请求进入 Controller 时通常已经有
当前 Span，Agents-Flex 的 Span 会自动成为子节点。

没有上游 instrumentation 时，可以在业务入口创建一个父 Span：

```java
Span requestSpan = Observability.getTracer()
    .spanBuilder("business.ai.request")
    .startSpan();

try (Scope ignored = requestSpan.makeCurrent()) {
    chatModel.chat(prompt, options);
    toolExecutor.execute();
} catch (RuntimeException error) {
    requestSpan.recordException(error);
    requestSpan.setStatus(StatusCode.ERROR);
    throw error;
} finally {
    requestSpan.end();
}
```

这样 Chat 和 Tool 才会作为同一个业务父 Span 下的步骤出现。父 Span 名称和业务属性由宿主系统定义。

## 数据越多越好吗

不是。可观测数据需要在诊断价值、成本和隐私之间平衡：

- Trace 太多会增加存储和网络成本，生产环境可使用 Sampler；
- Metric 标签太细会产生高基数时间序列；
- Prompt、响应和工具参数可能包含个人信息或密钥；
- JDBC 长期不清理会持续增长，应配置分区、归档或保留周期；
- Exporter 队列在进程崩溃时可能丢失，它不是业务消息队列。

建议先只采集元数据、耗时和错误；遇到明确需求后，再审慎增加业务属性或内容。

## 推荐学习顺序

1. 阅读[典型场景与实践](./scenarios)，理解数据如何帮助排查真实问题；
2. 按[快速开始](./getting-started)把 Span 输出到本地日志；
3. 阅读[模型与 HTTP 可观测](./model)和[工具调用可观测](./tool)了解自动采集内容；
4. 根据部署环境选择 OTLP、[JDBC](./jdbc)或[执行级路由](./runtime-routing)；
5. 上线前阅读[故障排查与生产建议](./troubleshooting)。

</div>
