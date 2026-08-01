---
title: 事件、监听器与审计
description: 使用 AgentListener 和 AgentRunEventStore 观察执行过程、持久化事件并断点续读。
---

# 事件、监听器与审计

<div v-pre>

## 三种观察机制

### AgentListener

进程内同步回调：

```java
runner.addListener(new AgentListener() {
    @Override
    public void onRunSuspended(AgentRun run, AgentSuspension suspension) {
        uiPublisher.publish(run.getId(), run.getStatus());
    }

    @Override
    public void onBudgetExceeded(AgentRun run, String reason) {
        metrics.increment("agent.budget.exceeded", reason);
    }
});
```

Listener 异常会被 Runner 捕获并记录，不改变主执行流程。

### AgentRuntimeEventStream

进程内细粒度实时事件流：

```java
runner.addRuntimeEventListener(event -> {
    websocket.send(event.getRunId(), event);
});
```

每个 Run 的 `sequence` 在当前 Runner 内单调递增。事件 data 可以携带字符串、ToolCall、结构化审批
决定等运行时对象，适合即时 UI 和追踪，不作为跨进程恢复依据。

常用实时事件包括：

| 类型 | 数据 |
| --- | --- |
| `MODEL_TEXT_DELTA` | 模型文本增量 |
| `MODEL_REASONING_DELTA` | 模型推理内容增量 |
| `MODEL_TOOL_CALL_DELTA` | 工具名称和参数增量 |
| `TOOL_PROGRESS` | 工具主动上报的进度和业务数据 |
| `COMMAND_SUBMITTED` / `COMMAND_CONSUMED` | 恢复命令进入和离开 Inbox |
| `CONTEXT_COMPACTED` | 压缩前后消息数量 |
| `TOOL_RESULT_OFFLOADED` | artifactId、大小和校验信息 |

当 `AgentInvocationContext.streaming=true` 时，Runner 使用 `ChatModel.chatStream(...)`，把回调转成实时
delta 事件，并在流关闭后继续走同一套 ToolCall、Checkpoint、审批和预算逻辑。

### AgentRunEventStore

追加式持久化事件：

```java
AgentRunner runner = new AgentRunner(
    runStore,
    agentRegistry,
    toolRegistry,
    eventStore
);
```

每个 Run 内 sequence 严格递增，eventId 用于幂等追加。

## 事件类型

| 事件 | 含义 |
| --- | --- |
| `RUN_STARTED` | Run 第一次开始 |
| `MODEL_STARTED` / `MODEL_COMPLETED` | 模型回合 |
| `TOOL_STARTED` / `TOOL_COMPLETED` / `TOOL_FAILED` | 工具执行 |
| `CHECKPOINT_SAVED` | 稳定状态保存成功 |
| `RUN_SUSPENDED` / `RUN_RESUMED` | 暂停和恢复 |
| `TOOL_APPROVAL_REQUESTED` | 请求工具审批 |
| `RETRY_SCHEDULED` | 安排持久化重试 |
| `BUDGET_EXCEEDED` | 预算耗尽 |
| `CHILD_STARTED` | 创建子 Run |
| `RUN_COMPLETED` / `RUN_FAILED` / `RUN_CANCELLED` | 最终状态 |
| `CANCELLATION_REQUESTED` | 控制面已持久化取消请求 |
| `MAX_ITERATIONS_REACHED` | 达到模型迭代上限 |
| `MAX_STEPS_REACHED` | 运行模式达到总 step 上限 |

## 增量读取

```java
long cursor = 0;

List<AgentRunEvent> page = eventStore.load(runId, cursor, 100);

for (AgentRunEvent event : page) {
    handle(event);
    cursor = event.getSequence();
}
```

消费端持久化 cursor 后，可以从上次 sequence 继续，不需要重复读取整个事件流。

## Attributes

事件 attributes 只保存字符串，常见字段：

```text
iteration
maxIterations
remainingIterations
hasToolCalls
toolCallId
toolName
error
status
version
reason
childRunId
agentId
agentVersion
executionModeId
executionModeVersion
```

不要把完整 Prompt、工具参数、API Key 和隐私数据无条件写入事件。

## 添加平台审计维度

```java
runner.addEventEnricher((run, eventType) -> {
    Map<String, String> values = new HashMap<>();
    values.put("accountId", String.valueOf(run.getMetadata().get("accountId")));
    values.put("module", String.valueOf(run.getMetadata().get("module")));
    values.put("taskType", String.valueOf(run.getMetadata().get("taskType")));
    return values;
});
```

增强器只负责添加字符串索引属性。完整请求内容、工具参数和结果更适合由 `AgentListener` 脱敏后写入专用审计表。

## 事件与 Checkpoint 的关系

当前接口将 EventStore 和 RunStore 分开。生产数据库若要求严格一致性，可选择：

1. 同库事务写 Snapshot 和 Event；
2. Snapshot 表带 Outbox，事务后异步转为事件；
3. 消费端根据 eventId 和 sequence 幂等处理；
4. 接受事件用于观察而 Snapshot 才是状态事实来源。

::: tip 状态事实来源
恢复执行时以 `AgentRunStore` 为准。EventStore 用于审计和消费，不应通过回放事件猜测当前 Run 状态。
:::

## 如何选择

- 只做少量同步观察时使用 `AgentListener`；
- 页面需要边生成边展示、工具进度条或命令状态时使用 `AgentRuntimeEventStream`；
- 审计、报表、断点消费和跨进程查询使用 `AgentRunEventStore`。

实时事件默认不持久化。需要保存 token delta 或工具进度时，平台可以订阅 Runtime Event，再写入自己的
事件表或消息系统；不要把每个 token 都塞进 AgentRunSnapshot。

## 下一步

- [架构与核心组件](./architecture.md)
- [生产实践](./production.md)
- [长任务恢复场景](./scenarios/long-running-task.md)

</div>
