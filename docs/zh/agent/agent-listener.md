---
title: AgentListener 生命周期监听器
description: 使用 AgentListener 观察 AgentRun、模型、工具、Checkpoint、暂停恢复、重试和终止状态。
---

# AgentListener 生命周期监听器

## 概述

`AgentListener` 是 `AgentRunner` 提供的进程内生命周期监听接口。它让应用在 AgentRun 开始、模型调用、工具执行、Checkpoint、暂停恢复、重试和终止等关键时刻执行自己的观察逻辑。

例如，后台报告 Agent 完成后需要更新页面状态；工具调用失败时需要记录结构化日志；Run 等待审批时需要向业务通知服务发送提醒。这些操作都需要知道运行到了哪个生命周期节点，但不应该改变 Agent 的执行决策，此时适合使用 `AgentListener`。

它的定位是“观察已经发生的事情”，而不是“决定接下来做什么”：

- 需要记录日志、更新进程内指标或触发轻量通知时，使用 `AgentListener`；
- 需要修改 Prompt、拦截模型或工具调用时，使用 `AgentMiddleware`；
- 需要 Token、ToolCall 参数增量等细粒度流式数据时，使用 `AgentRuntimeEventStream`；
- 需要跨进程查询、断点消费或可靠审计时，使用 `AgentRunEventStore`。

## 快速开发

所有回调都有默认空实现，只需要覆盖关心的方法：

```java
AgentListener listener = new AgentListener() {
    @Override
    public void onRunStart(AgentRun run) {
        System.out.println("开始执行：" + run.getId());
    }

    @Override
    public void onToolStart(AgentRun run, ToolCall toolCall) {
        System.out.println("调用工具：" + toolCall.getName());
    }

    @Override
    public void onRunComplete(AgentRun run) {
        System.out.println("执行完成：" + run.getFinalOutput());
    }

    @Override
    public void onRunFailed(AgentRun run, Throwable error) {
        System.err.println("执行失败：" + error.getMessage());
    }
};

AgentRunner runner = AgentRunner.builder()
    .agentLoader(agentLoader)
    .build()
    .addListener(listener);

AgentRun run = runner.run(agent, "查询订单 A1024");
```

监听器注册在 Runner 上。此后由该 Runner 推进的所有 Run 都会触发它，因此通常在应用初始化 Runner 时统一注册，而不是在每次请求中重复添加。

## 基本执行顺序

一次调用工具后正常完成的 Run，主要回调顺序如下：

```text
onCheckpoint（初始 READY 状态）
onRunStart
  onModelStart
  onModelEnd
  onCheckpoint（保存待执行 ToolCall）
  onToolStart
  onCheckpoint（保存 ToolMessage）
  onToolEnd
  onCheckpoint（返回模型阶段）
  onModelStart
  onModelEnd
  onCheckpoint（保存 COMPLETED 状态）
onRunComplete
```

实际序列取决于模型是否调用工具、工具是否需要审批、是否发生重试，以及执行模式是否保存额外 Checkpoint。应用应按每个回调表达的事实处理，不应依赖固定数量的模型或 Checkpoint 回调。

初始 `onCheckpoint` 在 `runner.start(...)` 创建并保存 `READY` Run 时发生，早于第一次实际推进。`onRunStart` 在 Run 第一次被 Runner 推进时触发一次。Run 从 Store 恢复后继续执行，不会再次产生“首次开始”回调。一个 Runner 可以并发推进多个 Run，不同 Run 的回调可能交错。

## Run 生命周期回调

### 开始与正常完成

```java
@Override
public void onRunStart(AgentRun run) {
    metrics.increment("agent.run.started", run.getAgent().getId());
}

@Override
public void onRunComplete(AgentRun run) {
    resultNotifier.completed(run.getId(), run.getFinalOutput());
}
```

`onRunComplete` 只表示模型已经返回最终消息并且 Run 正常进入 `COMPLETED`。失败、取消、预算耗尽和达到循环上限都有各自回调，不会同时触发 `onRunComplete`。

### 失败与取消

```java
@Override
public void onRunFailed(AgentRun run, Throwable error) {
    log.error("AgentRun failed, runId={}", run.getId(), error);
}

@Override
public void onRunCancelled(AgentRun run) {
    log.info("AgentRun cancelled, runId={}", run.getId());
}
```

`onRunFailed` 表示异常已经被运行时判定为不可恢复，或者自动重试次数已经耗尽。可恢复错误在安排下一次执行时先触发 `onRetryScheduled`，并不会立即触发最终失败回调。

取消采用协作式语义。控制面提交取消请求不代表回调立刻发生；Runner 在执行边界读取到取消标记并把 Run 置为 `CANCELLED` 后，才触发 `onRunCancelled`。

### 循环和预算终止

| 回调 | 对应结果 |
| --- | --- |
| `onMaxIterationsReached(run)` | 模型调用次数达到 `maxIterations` |
| `onMaxStepsReached(run)` | 执行模式推进次数达到 `maxSteps` |
| `onBudgetExceeded(run, reason)` | 时间、Token 或工具调用预算耗尽 |

预算回调中的 `reason` 可用于区分 `maxDurationMillis`、`maxInputTokens`、`maxOutputTokens`、`maxTotalTokens` 和 `maxToolCalls` 等原因。生产监控应把这些终止状态独立统计，不能全部归类为模型失败。

## 模型调用回调

```java
private final ConcurrentMap<String, Long> modelStartedAt =
    new ConcurrentHashMap<>();

@Override
public void onModelStart(AgentRun run) {
    modelStartedAt.put(run.getId(), System.nanoTime());
}

@Override
public void onModelEnd(AgentRun run, AiMessageResponse response) {
    Long startedAt = modelStartedAt.remove(run.getId());
    if (startedAt != null) {
        modelLatency.record(System.nanoTime() - startedAt);
    }
}
```

`onModelStart` 在每次调用 ChatModel 前触发，此时 Run 的迭代次数已经增加。`onModelEnd` 在模型返回并通过基础响应校验后触发，参数是模型的原始 `AiMessageResponse`，可以读取消息和 usage 信息。

如果模型调用直接抛出异常，不会出现对应的 `onModelEnd`。后续可能触发 `onRetryScheduled` 或最终的 `onRunFailed`，因此计时器实现需要在这些回调中清理未完成记录，或者使用支持超时回收的指标工具。

`AgentListener` 不提供文本增量、reasoning 增量和 ToolCall 参数增量。流式页面应订阅 `AgentRuntimeEventStream`，而不是等待 `onModelEnd`。

## 工具调用回调

```java
@Override
public void onToolStart(AgentRun run, ToolCall call) {
    audit.info("tool started: runId={}, callId={}, name={}",
        run.getId(), call.getId(), call.getName());
}

@Override
public void onToolEnd(AgentRun run, ToolCall call, ToolMessage result) {
    audit.info("tool completed: runId={}, callId={}",
        run.getId(), call.getId());
}

@Override
public void onToolError(AgentRun run, ToolCall call, Throwable error) {
    audit.warn("tool failed: runId={}, callId={}",
        run.getId(), call.getId(), error);
}
```

`onToolStart` 在工具真正开始执行前触发。等待审批时工具尚未执行，因此先触发 `onToolApprovalRequested`；审批通过并恢复后，才会触发 `onToolStart`。

`onToolEnd` 接收到即将写入消息历史的 `ToolMessage`。当工具错误策略为 `RETURN_ERROR_TO_MODEL` 时，失败会先触发 `onToolError`，随后还会触发 `onToolEnd`，其中的 ToolMessage 保存结构化错误并交给模型处理。因此不能简单地把每次 `onToolEnd` 都统计为业务工具成功，应结合 ToolMessage 或持久化事件类型设计指标。

工具结果可能包含敏感数据或大型内容。日志通常只记录 runId、ToolCall ID、工具名称、耗时和结果分类，不应直接输出完整参数与结果。

## Checkpoint、暂停与恢复

### Checkpoint 已保存

```java
@Override
public void onCheckpoint(AgentRun run, AgentRunSnapshot snapshot) {
    statusCache.put(run.getId(), snapshot.getStatus());
}
```

`onCheckpoint` 在稳定状态成功写入 `AgentRunStore` 后触发。传入的 Snapshot 已包含 Store 分配的新版本，因此适合刷新非权威缓存或更新当前页面状态。

这个回调不应被当作数据库事务的一部分：监听器失败不会回滚已经保存的 Checkpoint。如果业务动作必须与 Snapshot 原子提交，应在自定义 Store 或业务事务边界中设计，而不是依赖 Listener。

### 等待外部事件

```java
@Override
public void onRunSuspended(AgentRun run, AgentSuspension suspension) {
    waitingTaskView.update(run.getId(), suspension.getType());
}

@Override
public void onRunResumed(AgentRun run, AgentResumeCommand command) {
    waitingTaskView.markResumed(run.getId(), command.getType());
}
```

`onRunSuspended` 表示 Run 已进入需要外部事件的阻塞状态，例如等待用户补充信息、工具审批或子 Agent。`onRunResumed` 表示恢复命令已经通过类型和 correlation ID 校验，Run 即将继续推进。

对于工具审批，还可以使用更具体的回调：

```java
@Override
public void onToolApprovalRequested(AgentRun run, ToolCall call) {
    approvalNotifier.notify(run.getId(), call.getId(), call.getName());
}
```

如果通知必须保证最终送达，应由持久化事件或业务 Outbox 驱动。Listener 适合即时提醒，但进程在回调前退出时不会自动补发。

## 重试与子 Agent

```java
@Override
public void onRetryScheduled(AgentRun run, Throwable error) {
    log.warn("retry scheduled, runId={}, retry={}, nextRunAt={}",
        run.getId(), run.getRetryCount(), run.getNextRunAt(), error);
}

@Override
public void onChildStarted(AgentRun parent, AgentRun child) {
    traceRelation(parent.getId(), child.getId());
}
```

`onRetryScheduled` 在可恢复异常已经计算退避时间并写入运行状态时触发。此时可以展示下次执行时间，但不能在 Listener 中自行再次调用 `runner.run(...)`，否则会绕过 Worker 调度并造成并发推进。

`onChildStarted` 表示父 Run 已创建子 Run 并进入等待状态。子 Run 后续由同一个或其他 Worker 推进，它拥有自己的生命周期回调。跨进程构建完整父子时间线时，应以 Snapshot 父子 ID 和持久化事件为准。

## 回调执行模型

Listener 在触发生命周期事件的 Runner 线程中同步执行。Runner 内部使用线程安全列表保存监听器，但这不等于监听器实现自动线程安全：同一个 Listener 实例可能同时收到不同 Run 的回调。

实现时应遵循以下原则：

- 回调快速返回，不执行长时间网络请求或阻塞等待；
- 共享状态使用并发集合、原子类型或外部线程安全指标库；
- 需要异步处理时，只把最小不可变数据投递到有界队列；
- 不在回调中再次推进、恢复或取消当前 Run；
- 不修改 Run、ToolCall、Snapshot 或响应对象；
- 记录敏感内容前完成脱敏。

单个 Listener 抛出的 `RuntimeException` 会被 Runner 捕获并记录，不会阻止后续 Listener，也不会改变 AgentRun 的主流程。这保证了观察逻辑不会让业务任务失败，但也意味着监听失败需要独立监控。

## 与 Middleware 的区别

两者都能看到运行过程，但目的不同：

| 能力 | `AgentListener` | `AgentMiddleware` |
| --- | --- | --- |
| 观察生命周期 | 适合 | 可以，但不是主要用途 |
| 修改 Prompt | 不支持 | 支持 |
| 包裹模型调用 | 不支持 | 支持 |
| 拦截或替换工具结果 | 不支持 | 支持 |
| 影响执行决策 | 不应 | 可以按链式契约实现 |
| 单个扩展异常 | 被隔离，不影响 Run | 通常进入执行错误流程 |

权限校验、工具缓存、调用参数改写等会影响执行结果的逻辑应放在 Middleware 或 Tool 内。Listener 只做旁路观察，职责越简单，运行时行为越容易推断。

## 与两类事件机制的区别

| 机制 | 数据粒度 | 是否持久化 | 典型用途 |
| --- | --- | --- | --- |
| `AgentListener` | Java 对象级生命周期 | 否 | 本地日志、简单指标、轻量通知 |
| `AgentRuntimeEventStream` | 包含流式增量的细粒度事件 | 否 | WebSocket、SSE、实时 tracing |
| `AgentRunEventStore` | 带 sequence 的生命周期事件 | 是 | 审计、时间线、报表、断点消费 |

一个生产应用可以同时使用三者。例如控制台使用 Listener 打印最终状态，Web 页面使用 RuntimeEventStream 展示模型输出，审计服务从 EventStore 按 sequence 读取永久记录。它们面向不同可靠性和数据粒度，不需要互相替代。

## 完整监听器示例

实际项目通常封装一个只依赖日志和指标服务的实现：

```java
public final class MetricsAgentListener implements AgentListener {
    private final AgentMetrics metrics;

    public MetricsAgentListener(AgentMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void onRunStart(AgentRun run) {
        metrics.runStarted(run.getAgent().getId());
    }

    @Override
    public void onToolEnd(AgentRun run, ToolCall call, ToolMessage result) {
        metrics.toolCompleted(run.getAgent().getId(), call.getName());
    }

    @Override
    public void onRetryScheduled(AgentRun run, Throwable error) {
        metrics.retryScheduled(run.getAgent().getId(), error.getClass().getName());
    }

    @Override
    public void onRunComplete(AgentRun run) {
        metrics.runTerminated(run.getAgent().getId(), "completed");
    }

    @Override
    public void onRunFailed(AgentRun run, Throwable error) {
        metrics.runTerminated(run.getAgent().getId(), "failed");
    }

    @Override
    public void onBudgetExceeded(AgentRun run, String reason) {
        metrics.runTerminated(run.getAgent().getId(), "budget:" + reason);
    }
}
```

指标标签应使用 Agent ID、版本、工具名和有限状态等低基数字段。不要直接把 runId、用户输入或错误消息作为指标标签；这些数据更适合日志和持久化事件。

## 使用建议

适合使用 `AgentListener` 的场景包括单实例控制台输出、本地调试、进程内 Micrometer 指标、非关键缓存刷新和可丢失的即时通知。

以下需求不应只依赖 Listener：

- 必须送达的审批通知或业务消息；
- Worker 崩溃后仍需查询的完整执行历史；
- 跨节点统一的 Run 时间线；
- 每个模型 Token 的流式展示；
- 权限、审批或工具执行结果的控制决策。

在这些场景中，应分别组合 Command Inbox、持久化事件、RuntimeEventStream、Middleware 和业务 Outbox。Listener 保持为轻量观察器，才能在不干扰 Agent 主流程的前提下提供清晰的生命周期入口。
