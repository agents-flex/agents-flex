---
title: Agent 与 AgentRun
description: 理解不可变 Agent 定义、单次 AgentRun 状态、执行策略和稳定 ID 的设计。
---

# Agent 与 AgentRun

<div v-pre>

## 为什么要分成两个对象

`Agent` 和 `AgentRun` 的关系类似“程序定义”和“程序进程”：

- `Agent` 描述能力，可以复用；
- `AgentRun` 表示一次具体执行，不能复用。

如果把消息和状态直接放进 Agent，同一个 Agent 就无法安全地同时处理多个用户请求，也很难做 Checkpoint 和恢复。

## Agent 定义

### 稳定 ID 与名称

```java
Agent agent = Agent.builder("order-assistant")
    .id("order-assistant")
    .version("1")
    .chatModel(chatModel)
    .build();
```

- `name` 面向日志和业务表达；
- `id` 表示逻辑 Agent；
- `version` 表示该 Agent 的已发布定义版本；
- 未设置 `id` 时默认使用 `name`。

Checkpoint 保存 `agentId + agentVersion`。配置平台发布新版本后，应继续保留历史版本的解析能力，直到使用该版本的 Run 全部终止或完成归档。

### 运行模式和上下文策略

```java
.executionMode(ToolCallingAgentExecutionMode.INSTANCE)
.contextPolicy(AgentContextPolicy.recentMessages(20))
```

- `AgentExecutionMode` 决定 `step()` 如何推进；
- `AgentContextPolicy` 只控制模型本次可见的消息，不删除 Checkpoint 中的完整历史；
- 默认上下文策略保持 `MemoryPrompt` 的默认行为。

平台还可以通过 attributes 保存配置说明和来源：

```java
.attribute("modeCode", "TOOL_CALLING")
.attribute("taskType", "data-analysis")
.attribute("publishedBy", "admin-42")
```

### 系统指令

```java
.instructions("你是订单助手。修改订单前必须确认订单号和用户身份。")
```

指令会作为 SystemMessage 装配进每个 AgentRun 的 `MemoryPrompt`。

### 模型与 ChatOptions

```java
.chatModel(chatModel)
.chatOptions(ChatOptions.builder()
    .temperature(0.2f)
    .maxTokens(1200)
    .build())
```

AgentRunner 每次模型回合都使用这组配置。模型请求自身的网络重试与 AgentRun 的持久化重试是两层机制，详见[生产实践](./production.md)。

### 工具和拦截器

```java
.tools(Arrays.asList(queryOrder, cancelOrder))
.toolInterceptor(auditInterceptor)
```

同一个 Agent 内工具名必须唯一。ToolCall 通过名称选择工具，因此工具名也是 `AgentToolReference` 持久化身份的一部分。

### 执行策略

```java
AgentExecutionPolicy policy = AgentExecutionPolicy.builder()
    .maxIterations(12)
    .maxSteps(200)
    .toolErrorStrategy(ToolErrorStrategy.FAIL_RUN)
    .retryPolicy(AgentRetryPolicy.builder()
        .maxRetries(2)
        .initialDelayMillis(1_000)
        .maxDelayMillis(10_000)
        .multiplier(2.0)
        .build())
    .budget(AgentBudget.builder()
        .maxDurationMillis(120_000)
        .maxTotalTokens(30_000)
        .maxToolCalls(20)
        .build())
    .build();
```

| 配置 | 含义 |
| --- | --- |
| `maxIterations` | 最多允许多少次模型调用 |
| `maxSteps` | 运行模式最多允许推进多少个 step，防止自定义模式死循环 |
| `toolErrorStrategy` | 工具失败时终止，或把错误交给模型 |
| `retryPolicy` | 模型或工具可恢复异常的 Run 级重试 |
| `budget` | 时间、Token 和工具调用次数硬限制 |

## AgentRun 状态

创建并持久化 Run：

```java
AgentRun run = runner.start(agent, "取消订单 A1001");
```

常用读取接口：

```java
run.getId();
run.getAgent();
run.getPrompt();
run.getStatus();
run.getPhase();
run.getPendingToolCalls();
run.getSuspension();
run.getIterationCount();
run.getTotalTokens();
run.getFinalOutput();
run.getError();
run.getVersion();
run.getParentRunId();
run.getRootRunId();
run.getMetadata();
run.getExecutionPolicy();
run.getModeState();
```

### Metadata

业务可以附加 Trace ID、租户 ID 和业务单号：

```java
run.putMetadata("tenantId", tenantId);
run.putMetadata("requestId", requestId);
runner.checkpoint(run);
```

Metadata 会进入 Snapshot。值应当可被目标 Store 序列化，不要放入数据库连接、线程、模型或 Tool 实例。

创建 Run 时需要原子保存账号、模块和单次运行策略时，优先使用 `AgentRunOptions`：

```java
AgentRunOptions options = AgentRunOptions.builder()
    .executionPolicy(recommendedPolicy)
    .metadata("accountId", accountId)
    .metadata("module", "agent-console")
    .metadata("taskType", "data-analysis")
    .build();

AgentRun run = runner.start(agent, input, options);
```

有效执行策略会被冻结到 AgentRun 并写入 Snapshot。即使平台随后调整推荐迭代次数，当前 Run 仍按创建时的限制执行。

### 取消

```java
AgentRun requested = runner.requestCancellation(runId);
```

取消请求直接写入 `AgentRunStore`，不依赖调用方继续持有原来的内存对象。READY、RUNNING 和各种等待状态都可以收到取消请求；Worker 会领取尚未终止的已取消 Run，并保存为 `CANCELLED`。

取消是协作式的。它不会强行终止一个已经进入底层 HTTP 客户端或 Tool 方法的调用，而是在模型调用返回、工具调用完成或下一步开始等安全边界停止继续推进。需要及时停止单个外部调用时，仍应使用底层客户端的超时或取消能力。

## AgentRunSnapshot

```java
AgentRunSnapshot snapshot = run.toSnapshot();
```

Snapshot 与 Run 隔离，适合 JSON、数据库字段或其他持久化编码。恢复时：

```java
Agent agent = agentRegistry.resolve(
    snapshot.getAgentId(),
    snapshot.getAgentVersion()
);
AgentRun restored = AgentRun.fromSnapshot(agent, snapshot);
```

通常不需要手工执行上述过程，直接使用：

```java
AgentRun restored = runner.restore(runId);
```

## 线程安全边界

- `Agent` 是不可变定义，但内部 ChatModel 和 Tool 是否线程安全取决于具体实现；
- `AgentRun` 是可变状态，不应被多个 Runner 线程同时推进；
- 多 Worker 并发由 Store 的 CAS 和 Lease 控制；
- Listener 集合应在开始执行前完成配置。

## 下一步

- [执行循环与状态](./execution-lifecycle.md)
- [Checkpoint 与中断恢复](./checkpoint-resume.md)
- [预算、重试与生产实践](./production.md)

</div>
