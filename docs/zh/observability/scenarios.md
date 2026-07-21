<div v-pre>

# 典型场景与实践

## 先按问题选择数据

| 用户遇到的问题 | 首先看什么 | 原因 |
| --- | --- | --- |
| 某一次回答特别慢 | Trace 调用树 | 能看到每一步的独立耗时 |
| 最近所有回答都变慢 | Metric 延迟分布 | 能判断整体趋势和影响范围 |
| 工具偶发失败 | Tool Span + 子 HTTP Span | 能关联参数类型、异常和状态码 |
| 用户拿着会话 ID 投诉 | 带 conversation ID 的 Span | 可以定位到这一条业务调用链 |
| 不同业务对象要进入不同 APM | Telemetry Route | 每次执行动态选择后端 |
| APM 和 MySQL 都需要一份数据 | 多 destination Route | 同一份 Span 独立扇出到两个后端 |
| 没有 APM，只想先验证 | Logging Exporter | 不需要部署额外系统 |

## 场景一：用户说“这次回答为什么用了 12 秒”

### 问题

一次回答可能包含模型请求、工具调用和多次 HTTP 请求。只记录总耗时只能确认“慢”，不能说明“慢在哪里”。

### 接入

保持 ChatModel 可观测开关开启，并正常通过 `ToolExecutor` 和 `AgentsFlexHttpClient` 执行：

```java
config.setObservabilityEnabled(true);
AiMessageResponse response = chatModel.chat(prompt, options);
```

如果业务入口已经有父 Context，框架埋点会形成类似调用树：

```text
business.ai.request             12.0s
├── qwen.chat                    1.9s
│   └── http.client.request      1.8s  200
└── tool.searchKnowledge         9.8s
    └── http.client.request      9.7s  200
```

### 可以得到什么结论

模型 HTTP 只用了 1.9 秒，主要耗时来自知识搜索工具。下一步应该检查搜索服务、网络或工具内部逻辑，而不是
盲目更换模型。

如果没有 Web instrumentation、Java Agent 或业务父 Span，Chat 与随后执行的 Tool 可能是两条独立 Trace。
创建业务父 Span 的方式见[Context 为什么重要](./concepts#context-为什么重要)。

### 容易误判的地方

- 流式首字延迟和完整流式耗时不是一回事；当前 Span 覆盖到 `onStop`/`onFailure`；
- `getResponse(...)` 的 HTTP Span 会持续到响应体读完或关闭；未关闭 Response 会让耗时看起来异常；
- 如果工具切换线程但没有传播 Context，它可能显示为另一条 Trace。

## 场景二：发布后模型整体变慢

### 问题

单条 Trace 只能代表一次请求。要判断发布前后整体是否恶化，需要观察一段时间内的延迟分布和错误率。

### 使用的数据

- `llm.request.latency`：按 provider、model、operation 查看 P50/P95/P99；
- `llm.request.count`：确认请求量是否变化；
- `llm.request.error.count`：计算失败率；
- `http.client.request.duration`：判断慢在模型服务还是应用内部。

例如监控平台可以构建以下视图：

| 图表 | 分组 | 用途 |
| --- | --- | --- |
| 模型 P95 延迟 | model、operation | 比较不同模型和同步/流式调用 |
| 模型错误率 | model | 发现某个模型单独异常 |
| HTTP P95 延迟 | server.address | 判断具体远程服务是否变慢 |
| 请求量 | provider | 排除流量上涨造成的容量问题 |

### 可以得到什么结论

如果 Chat 和 HTTP 延迟同时上涨，问题更可能在模型服务或网络；如果只有 Chat Span 上涨，可能是响应处理、
流式消费或自定义 interceptor 变慢。

### 注意

Metric 默认每 60 秒导出一次。刚发起请求后立即查询可能看不到数据，这不表示采集失败。

## 场景三：工具偶发失败，但本地无法复现

### 问题

工具依赖参数、用户上下文和远程接口，错误可能只在特定请求出现。

### 接入业务关联信息

```java
Attributes attributes = Attributes.builder()
    .put("app.workflow.id", workflowId)
    .put("app.subject.id", subjectId)
    .build();

try (Scope ignored = Observability.useRuntime(route, attributes)) {
    toolExecutor.execute();
}
```

然后在 Trace 中检查：

- `tool.name`：具体失败工具；
- Span status：是否为 `ERROR`；
- exception event：异常类型和堆栈；
- 子 `http.client.request`：远程状态码和 host；
- `app.workflow.id`：关联宿主系统的业务流程。

### 是否应该采集工具参数

默认不要。先用工具名、异常和业务 ID 定位；确实需要时再开启：

```properties
agentsflex.otel.capture.content=true
```

开启后参数会递归脱敏并截断，但自由文本中的个人信息无法保证识别。生产环境应先完成数据安全评审。

## 场景四：客服根据会话 ID 排查用户投诉

### 问题

客服只有 `conversation-42`，需要找到用户当时调用了什么模型、工具在哪里失败。

### 接入

```java
ChatOptions options = new ChatOptions();
options.setContextConversationId("conversation-42");
options.setContextAccountId("account-7");

chatModel.chat(prompt, options);
```

Chat Span 会包含：

- `gen_ai.conversation.id=conversation-42`
- `enduser.id=account-7`

使用 JDBC Exporter 时，这两个值还会进入 Span 表的 `conversation_id` 和 `account_id` 独立列。用户自己的
系统可以先按会话 ID 找到 `trace_id`，再按 trace ID 还原整条调用链。

### 为什么不把会话 ID 放入 Metric

会话数量通常非常大。每个会话一个 Metric 时间序列会造成高基数问题。会话排查属于单次请求定位，应使用
Span，而不是 Metric。

## 场景五：不同业务对象发送到不同 APM

### 问题

宿主系统自己定义了“智能体”或工作流：客服业务发送到公司 APM，内部测试业务只写 MySQL。这个业务概念
不对应 Agents-Flex 的 `ReActAgent` 或 `IAgent`。

### 设计

业务对象只保存 `telemetryRouteId`：

```text
customer-service  -> company-apm-route
internal-testing  -> mysql-route
```

执行时解析 Route：

```java
TelemetryRoute route = routes.require(definition.getTelemetryRouteId());

try (Scope ignored = Observability.useRuntime(route, Attributes.of(
    AttributeKey.stringKey("app.subject.id"), definition.getId()))) {
    chatModel.chat(prompt, options);
}
```

Scope 内的 Chat、Tool 和 HTTP 数据都会进入这条 Route。详细构建方式见
[按执行上下文路由](./runtime-routing)。

## 场景六：同一份数据同时进入 APM 和 MySQL

### 问题

运维团队使用 APM 做实时排障，业务团队希望在自己的系统中通过 MySQL 查询审计数据。

### 设计

在一个 `TelemetryRoute` 中配置两个 `TelemetryDestination`：

```text
同一个 Span
├── destination: company-apm -> 独立 BatchSpanProcessor -> OTLP
└── destination: audit-mysql -> 独立 BatchSpanProcessor -> JDBC
```

两个后端收到相同的 Trace ID 和 Span ID，但使用独立队列、超时和批大小。MySQL 变慢不会占用 APM 的导出
队列。

### 什么时候改用 Collector fan-out

如果后端很多、路由频繁变化、需要磁盘缓冲或可靠重试，应用只发送 OTLP 到 Collector，再由 Collector 分发
通常更简单。应用内多 destination 更适合后端数量少且配置稳定的场景。

## 场景七：没有 APM，先写入 MySQL 自己查询

### 问题

团队暂时没有 Jaeger、Tempo 或商业 APM，但已经有 MySQL 和内部管理系统。

### 接入路径

1. 添加 `agents-flex-observability-jdbc`；
2. 执行模块提供的 `mysql-schema.sql`；
3. 使用现有连接池创建 `JdbcTelemetryExporters`；
4. 通过全局自定义 Exporter 或 Telemetry Route 接入；
5. 在内部系统按 `trace_id`、`conversation_id`、时间和状态查询。

示例查询思路：

```sql
SELECT trace_id, span_id, parent_span_id, span_name,
       duration_nanos, status_code, attributes_json
FROM agents_flex_otel_spans
WHERE conversation_id = 'conversation-42'
ORDER BY start_epoch_nanos;
```

查询只是示意，实际字段索引、租户权限、分页和数据保留由用户系统负责。Agents-Flex JDBC 模块只负责写入。

## 从哪个场景开始

完全没有可观测经验时，建议按以下顺序：

1. 用 Logging Exporter 跑一次模型调用，认识 Span；
2. 故意让一个工具抛异常，观察 ERROR Span；
3. 等待一次 Metric 导出周期，区分 Trace 和 Metric；
4. 再接入团队已有的 APM、Collector 或 MySQL；
5. 最后添加业务 ID、Dashboard 和告警。

下一步直接跟随[快速开始](./getting-started)完成第一次本地观测。

</div>
