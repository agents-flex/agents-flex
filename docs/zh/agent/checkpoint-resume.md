---
title: Checkpoint 与中断恢复
description: 使用 AgentRunSnapshot、AgentRunStore、乐观锁和 Resume Command 实现可靠的跨请求恢复。
---

# Checkpoint 与中断恢复

<div v-pre>

## Checkpoint 保存什么

Runner 会在稳定边界调用 `AgentRunStore.save(...)`：

- Run 创建后；
- 模型产生 ToolCall 后；
- 每个 ToolMessage 写入后；
- Run 暂停或恢复后；
- 安排重试后；
- 进入任一终止状态后。

这使恢复后的 Runner 能够判断：模型是否已经作出决定、哪些工具已经完成、下一个工具是什么，以及应使用哪个持久化工具引用重新绑定实现。

## 手工保存

业务修改 metadata 或在外部边界保存时，可以显式 Checkpoint：

```java
run.putMetadata("businessId", "ORDER-A1001");
AgentRunSnapshot saved = runner.checkpoint(run);
System.out.println(saved.getVersion());
```

取消不要通过修改内存 Run 再手工 Checkpoint，应使用独立的持久化控制接口：

```java
runner.requestCancellation(runId);
```

取消标记是单调信号。生产 Store 必须保证后续普通 Checkpoint 不会把已经写入的取消标记清除，并让 `claimRunnable` 能领取尚未终止的已取消等待任务。

## 乐观锁

保存签名：

```java
AgentRunSnapshot save(
    AgentRunSnapshot snapshot,
    long expectedVersion
);
```

新建记录的 expectedVersion 是 `-1`。保存成功后版本加一。如果两个线程都读取 version 5：

```text
Worker A: save(expected=5) → 成功，Store version=6
Worker B: save(expected=5) → AgentRunVersionConflictException
```

发生冲突时，应重新加载最新 Snapshot，并根据业务语义决定当前操作是否仍然有效，避免过期状态覆盖最新进度。

## 恢复 Run

```java
AgentRun restored = runner.restore(runId);
```

如果当前请求需要把租户身份、认证服务或请求追踪重新交给 Middleware 和 Tool，应显式附加新的调用上下文：

```java
AgentRun restored = runner.restore(runId, currentInvocationContext);
```

`AgentInvocationContext` 不属于 Checkpoint。恢复时重新附加可以避免把旧认证对象、客户端和连接长期
序列化，也能确保权限按当前请求重新计算。

内部步骤：

1. 从 `AgentRunStore` 加载 Snapshot；
2. 使用 `AgentRegistry.resolve(agentId, agentVersion)` 获取原始定义版本；
3. 注册 Agent 工具；
4. 校验 executionMode ID 和版本；
5. 重新构造 MemoryPrompt、pending ToolCalls、pending ToolReferences、Suspension、有效执行策略、stepCount、modeState 和版本；
6. 在 TOOLS 阶段通过 `AgentToolRegistry.resolve(reference)` 重新绑定工具；
7. 返回可继续推进的 AgentRun。

每个 pending ToolCall 都必须存在以 ToolCall ID 为 key 的 `AgentToolReference`。Reference 缺失或 Registry 返回 `null` 表示 Checkpoint 无法确定原工具绑定，Runner 会终止该 Run，不会按工具名称猜测其他实现。

## 跨 Runner 恢复

```java
AgentRunStore sharedStore = databaseRunStore;
AgentRegistry sharedRegistry = applicationAgentRegistry;
AgentToolRegistry sharedTools = applicationToolRegistry;

AgentRunner first = new AgentRunner(sharedStore, sharedRegistry, sharedTools);
AgentRun waiting = first.run(agent, input);

// 另一台机器或另一个请求
AgentRunner second = new AgentRunner(sharedStore, sharedRegistry, sharedTools);
AgentRun completed = second.resume(
    waiting.getId(),
    AgentResumeCommand.approveTool(waiting.getSuspension().getCorrelationId())
);
```

## 等待用户输入

业务可以主动暂停：

```java
runner.suspend(run, AgentSuspension.userInput("请提供订单号"));
```

用户回复后：

```java
AgentRun result = runner.resume(
    runId,
    AgentResumeCommand.userInput("订单号 A1001")
);
```

用户输入会加入 Prompt，恢复到 Suspension 指定的阶段。

## 恢复命令校验

Runner 会拒绝：

- 非阻塞 Run 的重复 resume；
- 审批 Run 收到用户输入命令；
- correlationId 不匹配；
- 重试时间未到却提交 `retry()`；
- 空白用户输入；
- 已终止 Run 再次执行。

这些校验用于阻止重复 Webhook、迟到事件和错误任务 ID 破坏状态。

## 持久化 Command Inbox

跨请求恢复推荐先持久化命令：

```java
runner.submitCommand(
    webhookEventId,
    runId,
    AgentResumeCommand.approveTool(toolCallId)
);
```

`AgentRunCommandStore` 保存命令状态、处理次数和命令 Lease。`AgentWorker.pollAndRun(...)` 会先消费 Inbox，
再领取已恢复为可运行状态的 Run。

Runner 在保存恢复后的 Run 时同步写入 `agent.command.processed.<commandId>` 标记。若进程在 Checkpoint
成功后、Command ack 前退出，重新投递只会补做 ack，不会再次添加用户消息或重复应用审批。

`AgentWakeupListener` 可以通知调度器立即轮询，但通知不是可靠存储；即使通知丢失，命令仍会在下一次
定时轮询中被处理。

## 崩溃窗口与幂等

Checkpoint 能避免已经保存的工具结果再次执行，但无法消除所有外部副作用窗口：

```text
工具写入外部系统成功
  → 进程在保存 ToolMessage 前崩溃
  → 恢复后仍看到 pending ToolCall
```

因此，可靠写工具必须：

1. 使用 ToolCall ID 或业务请求 ID 作为幂等键；
2. 外部系统记录该幂等键和结果；
3. 重复调用时返回第一次结果；
4. 不依赖“Java 方法只会调用一次”的假设。

## Store 实现建议

关系数据库表至少包含：

```text
run_id              primary key
agent_id
agent_version
execution_mode_id
execution_mode_version
status
phase
snapshot_json       或拆分字段
version
next_run_at
lease_owner
lease_until
parent_run_id
root_run_id
updated_at
```

执行策略、modeState、pending ToolCalls 和 pending ToolReferences 可以包含在 `snapshot_json` 中。Reference metadata 必须与 Snapshot 一起原子保存，否则恢复时可能绑定到不同的工具定义。不要只保存最新 Agent 配置引用，否则历史 Run 恢复时可能发生配置漂移。

`save` 可以使用：

```sql
UPDATE agent_run
SET snapshot_json = ?, version = version + 1
WHERE run_id = ? AND version = ?
```

受影响行数为 0 时抛出版本冲突。

## 下一步

- [Worker 与 Lease](./worker-lease.md)
- [长任务恢复场景](./scenarios/long-running-task.md)
- [生产实践](./production.md)

</div>
