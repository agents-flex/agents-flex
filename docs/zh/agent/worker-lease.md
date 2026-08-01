---
title: Worker 与 Lease
description: 使用 AgentWorker、任务领取和租约机制可靠执行后台长任务与自动重试。
---

# Worker 与 Lease

<div v-pre>

## 为什么需要 Worker

同步 `runner.run(...)` 适合一次请求内可以完成的任务。以下情况更适合 Worker：

- 工具审批接口只负责接收命令，不希望阻塞 HTTP 请求；
- 自动重试要等到未来的 `nextRunAt`；
- 任务执行时间较长，需要从 Web 进程解耦；
- 多个应用实例共同消费任务；
- 进程崩溃后需要其他实例接管。

## Runnable Run

Store 会领取以下状态：

- `READY`；
- `RUNNING`；
- 已到 `nextRunAt` 的 `RETRY_SCHEDULED`。

审批、用户输入和子 Agent 等等待状态不能直接领取，必须先接受相应外部事件。

## 单次领取

```java
AgentWorker worker = new AgentWorker(
    "worker-shanghai-01",
    runner,
    30_000
);

List<AgentRun> results = worker.pollAndRun(20);
```

Worker 需要为恢复后的 Middleware 和 Tool 重建租户上下文时，可以提供：

```java
AgentWorker worker = new AgentWorker(
    "worker-shanghai-01",
    runner,
    30_000,
    snapshot -> invocationContextFactory.create(snapshot.getMetadata())
);
```

Provider 返回的 `AgentInvocationContext` 只作用于当前 Worker 执行，不会写回 Snapshot。

`pollAndRun` 的处理过程：

1. 从 `AgentRunCommandStore` 领取审批、用户输入等恢复命令；
2. 幂等应用命令，使对应 Run 进入可执行状态；
3. Store 原子领取一批 Run；
4. 写入 `leaseOwner` 和 `leaseUntil`；
5. Runner 恢复 Snapshot；
6. 在当前 Worker 租约上下文中执行；
7. 推进到终止或阻塞；
8. 如果是终止子 Run，尝试恢复父 Run；
9. 释放 Lease。

`AgentWakeupListener` 可在命令保存后通知本地调度器、消息队列或集群唤醒服务立即触发轮询。通知丢失
不会丢任务，因为 Command Inbox 仍保存待领取命令；定时轮询是最终保障。

## 自动轮询

```java
AgentWorker worker = new AgentWorker("worker-a", runner, 30_000);
worker.startPolling(1_000, 20);

Runtime.getRuntime().addShutdownHook(new Thread(worker::close));
```

重复调用 `startPolling` 不会创建多个线程。`close()` 会停止调度线程，但底层模型或工具是否能立即中断取决于具体实现。

## Lease 语义

领取时 Store 必须执行类似条件更新：

```sql
UPDATE agent_run
SET lease_owner = ?,
    lease_until = ?,
    version = version + 1
WHERE run_id = ?
  AND runnable = true
  AND (lease_until IS NULL OR lease_until <= ?)
```

Lease 不是分布式锁服务，而是 Run 记录上的临时执行权：

- 有效期内只有 owner 可以推进；
- Worker 正常结束后主动释放；
- Worker 崩溃后依靠过期时间恢复；
- Store 时间应尽量使用数据库时间，避免多机器时钟偏差。

## 续租

```java
AgentRunSnapshot renewed = worker.renewLease(run);
```

长工具调用前后或分段任务中可以续租。注意：当前 Runner 不会在一个阻塞的外部调用内部自动续租，生产系统应让单次底层超时小于 Lease，或者由独立心跳线程续租。

如果使用独立心跳线程，生产 Store 最好将 Lease/Fencing Token 与 Snapshot 的业务版本拆开；否则续租增加 version 时可能和正在保存的 Checkpoint 发生 CAS 冲突。另一种做法是在 step 边界由同一执行线程串行续租。

## 领取已取消任务

持久化取消请求可能发生在 `WAITING_FOR_USER`、`WAITING_FOR_APPROVAL` 或 `WAITING_FOR_CHILD`。这些状态平时不可领取，但当 `cancellationRequested=true` 时，`claimRunnable` 应允许 Worker 领取并把 Run 保存为 `CANCELLED`，避免等待任务永久停留。

## 重试调度

```java
AgentRetryPolicy retryPolicy = AgentRetryPolicy.builder()
    .maxRetries(3)
    .initialDelayMillis(1_000)
    .maxDelayMillis(30_000)
    .multiplier(2.0)
    .build();
```

异常后 Runner 保存：

```text
status = RETRY_SCHEDULED
retryCount += 1
nextRunAt = 当前时间 + delay
suspension.resumePhase = 失败时阶段
```

Worker 只在 `nextRunAt <= now` 后领取。恢复工具失败时仍使用原 pending ToolCall，不会重新请求模型决定同一个动作。

## 多 Worker 部署

```text
App / API
   │ 写入 READY 或恢复为 RUNNING
   ▼
共享 AgentRunStore
   ▲            ▲
Worker A      Worker B
```

正确性依赖 Store 原子语义，而不是 Java 进程内锁。多实例部署不要使用 `InMemoryAgentRunStore`。

## 常见问题

### Lease 过短

执行尚未完成租约就过期，其他 Worker 可能重新领取。提高 Lease、配置底层超时并定期续租。

### Lease 过长

Worker 崩溃后任务很久不能接管。根据最长安全步骤和恢复目标选择，一般配合心跳续租。

### Worker 重复执行副作用

Lease 降低并发重复执行，但不能消除“外部写成功、Checkpoint 未提交”的崩溃窗口。副作用工具仍需幂等。

### 父 Run 和子 Run 一起被领取

Store 应在父 Run 仍持有有效 Lease 时避免领取其子 Run，防止同一父子链被并发推进。

## 下一步

- [子 Agent](./subagents.md)
- [长任务恢复场景](./scenarios/long-running-task.md)
- [生产实践](./production.md)

</div>
