---
title: 场景：长任务与故障恢复
description: 使用持久化 Store、Worker Lease、自动重试、子 Agent 和事件流运行跨进程长任务。
---

# 场景：长任务与故障恢复

<div v-pre>

## 场景目标

长任务并不是让一个线程持续运行数小时，而是把执行拆成多个可以保存、停止和恢复的短步骤。每次模型调用、工具调用、等待审批、重试或子 Agent 切换，都形成一个稳定状态边界。

典型场景包括：

- 分析大型代码仓库并生成报告；
- 批量处理数据，期间调用多个外部服务；
- 部署、巡检和回滚；
- 需要人工确认的多步骤业务办理；
- 某些步骤由专业子 Agent 完成；
- 模型或工具出现限流后延迟重试。

## 生产拓扑

```text
API / 消息消费者
        |
        | start / submitResume
        v
持久化 AgentRunStore <----> 多个 AgentWorker
        |                         |
        | Checkpoint              | AgentRegistry / AgentToolRegistry
        v                         v
数据库或 Redis              ChatModel 与业务工具

追加式 AgentRunEventStore ---> WebSocket / 审计 / 监控
```

至少需要共享以下运行时资源：

| 资源 | 作用 |
| --- | --- |
| `AgentRunStore` | 保存当前状态、Checkpoint、重试时间和 Lease |
| `AgentRegistry` | 根据 `agentId + agentVersion` 解析 Agent 定义 |
| `AgentToolRegistry` | 根据持久化 `AgentToolReference` 解析可执行工具 |
| `AgentRunEventStore` | 保存可增量读取的运行事件 |
| `AgentTaskStore` | 启用任务规划时保存任务列表和进度 |

内存实现只适合本地开发和测试，进程退出后数据会消失，也不能协调多实例。

## 创建任务但不在请求线程执行

```java
AgentRun run = runner.start(agent, userGoal);

return new StartRunResponse(
    run.getId(),
    run.getStatus().name()
);
```

`start()` 会保存初始 Checkpoint，状态为可运行。API 随即返回，Worker 在后台领取任务。

## Worker 领取任务

```java
AgentWorker worker = new AgentWorker(
    "worker-shanghai-01",
    runner,
    30_000
);

worker.startPolling(1_000, 8);
```

参数含义：

- `workerId`：进程内唯一且在集群中稳定可识别；
- `leaseMillis`：一次领取的租约时长；
- `pollIntervalMillis`：没有任务时的轮询间隔；
- `batchSize`：每次最多领取的 Run 数量。

Store 的 `claimRunnable()` 必须原子筛选并更新记录，保证同一时刻只有一个 Worker 获得有效 Lease。

## Lease 为什么必要

如果两个 Worker 同时推进同一个 Run，可能重复调用模型、重复执行工具并覆盖消息历史。Lease 解决执行权问题，Checkpoint version/CAS 解决状态覆盖问题，两者职责不同。

```text
10:00:00 Worker A 领取 Run，leaseUntil=10:00:30
10:00:05 Worker B 轮询，不可领取
10:00:20 Worker A 续租到 10:00:50
10:00:25 Worker B 仍不可领取
10:00:51 若 A 未再续租，Worker B 可以接管
```

工具执行时间可能超过 Lease 时，应在安全位置续租：

```java
worker.renewLease(run);
```

生产实现通常由 Worker 心跳线程周期续租。续租失败表示执行权可能已经丢失，Worker 应停止继续写入，并让 CAS 冲突阻止旧状态覆盖新状态。

## Worker 崩溃恢复

Worker 崩溃后不会执行 `releaseLease()`。这不是永久锁死：Lease 到期后，其他 Worker 可以重新领取最新 Checkpoint。

恢复点取决于最后一次成功保存：

- 模型调用前已保存：可能重新调用模型；
- ToolCall 已保存但工具结果未保存：可能重新执行工具；
- ToolMessage 已保存：从下一模型步骤继续；
- 已进入等待状态：保持等待，不会被 Worker 当作可运行任务领取。

因此有副作用的工具必须具备业务幂等能力。Checkpoint 能恢复编排状态，但无法自动回滚已经提交到外部系统、尚未来得及写回结果的操作。

## 自动重试调度

```java
AgentExecutionPolicy policy = AgentExecutionPolicy.builder()
    .retryPolicy(AgentRetryPolicy.builder()
        .maxRetries(4)
        .initialDelayMillis(1_000)
        .maxDelayMillis(30_000)
        .build())
    .build();
```

可重试错误发生后，Runner 不会阻塞线程等待，而是：

1. 增加 retryCount；
2. 计算 `nextRunAt`；
3. 保存 `RETRY_SCHEDULED` Checkpoint；
4. 返回当前 Worker；
5. 时间到达后由任意 Worker 重新领取。

Store 查询必须只领取 `nextRunAt <= now` 的重试任务。多实例部署时建议使用数据库时间，避免机器时钟偏差。

## 子 Agent 的长任务协作

```java
AgentRun child = runner.startChild(
    parent,
    "security-review-agent",
    "检查本次变更的认证与权限风险"
);
```

创建子 Run 时，Store 应原子完成两件事：

- 父 Run 进入 `WAITING_FOR_CHILD`；
- 子 Run 保存为可领取状态。

Worker 完成子 Run 后调用 `resumeParentFromChild(child)`。Runner 将子结果写入父 Run，并使父 Run 重新可运行。该操作会检查父 Run 是否仍在等待当前 childRunId，以避免重复通知。

## 任务规划与 Worker

启用 `AgentPlanExecutor` 后，每个任务由独立子 Run 执行。计划处于 `WAITING` 时，具体等待原因来自 activeRun：

```java
AgentTaskProgress progress = planExecutor.getProgress(planId);

System.out.println(progress.getStatus());
System.out.println(progress.getCurrentTask().getTitle());
System.out.println(progress.getActiveRunStatus());
System.out.println(progress.getActiveSuspension());
```

Worker 在后台完成一个重试子 Run 后，再调用：

```java
planExecutor.runUntilBlocked(planId);
```

Executor 会读取已终止的 activeRun，更新任务结果并继续后续任务。

## 事件流的断点消费

Checkpoint 回答“现在是什么状态”，事件流回答“状态如何变化”。消费者使用 sequence 断点续读：

```java
long cursor = loadConsumerCursor(runId);

while (true) {
    List<AgentRunEvent> events = eventStore.load(runId, cursor, 100);
    if (events.isEmpty()) {
        break;
    }

    for (AgentRunEvent event : events) {
        publishToWebSocket(event);
        writeAuditRecord(event);
        cursor = event.getSequence();
    }

    saveConsumerCursor(runId, cursor);
}
```

消费者可能在处理完成但尚未保存 cursor 时崩溃，因此下游操作也应按 `eventId` 幂等。

## 预算保护

长任务必须有边界：

```java
AgentBudget budget = AgentBudget.builder()
    .maxDurationMillis(30 * 60_000L)
    .maxTotalTokens(200_000)
    .maxToolCalls(100)
    .build();
```

预算随 Checkpoint 保存，换 Worker 后继续累计。超过预算时 Run 进入 `BUDGET_EXCEEDED`，不会再执行后续模型或工具调用。

模型调用和工具调用都是外部操作。预算检查可以阻止下一步开始，但无法强制中断一个不响应取消的底层调用，因此仍应在 HTTP 客户端和工具实现中配置超时。

## Store 实现要求

生产 `AgentRunStore` 至少应保证：

- `save(snapshot, expectedVersion)` 使用 CAS 或条件更新；
- `saveParentAndChild()` 使用事务原子保存父子状态；
- `claimRunnable()` 原子领取且跳过有效 Lease；
- `renewLease()` 只允许当前 owner 更新；
- `releaseLease()` 只释放当前 owner 的 Lease；
- 状态、phase、消息、pending ToolCall、Tool Reference、预算、重试、Suspension 完整序列化；
- 对查询 `status + nextRunAt + leaseUntil` 建立索引；
- 明确时间来源和序列化版本升级策略。

## 故障演练

上线前至少验证以下场景：

1. ToolCall 保存后杀死 Worker，确认接管时工具幂等；
2. 工具成功但 Checkpoint 保存前杀死 Worker，确认不会产生重复业务结果；
3. Lease 续租失败，旧 Worker 不能继续覆盖状态；
4. 两个审批请求并发提交，只有一个成功；
5. 重试未到期时不会被领取，到期后只被一个 Worker 领取；
6. 子 Run 完成通知重复投递，父 Run 只追加一次结果；
7. Event 消费者从旧 cursor 重启，事件不丢失且下游不重复；
8. 达到 Token、时间和工具调用预算后可靠终止。

## 生产检查清单

- 使用数据库或可靠 KV 实现 RunStore、TaskStore 和 EventStore；
- Agent 和工具 ID 在所有 Worker 中保持一致；
- 副作用工具支持幂等键与可查询的业务结果；
- Lease 时长大于正常步骤耗时，并配置续租；
- Worker 设置并发上限、模型限流和优雅停机；
- 监控 READY、RUNNING、WAITING、RETRY_SCHEDULED 的数量和停留时间；
- 对 version conflict、Lease 丢失、重试耗尽和预算终止设置告警；
- 事件与 Checkpoint 中的敏感数据完成脱敏和访问控制。

## 延伸阅读

- [Worker 与 Lease](../worker-lease.md)
- [Checkpoint 与中断恢复](../checkpoint-resume.md)
- [子 Agent](../subagents.md)
- [事件、监听器与审计](../events.md)
- [生产实践](../production.md)

</div>
