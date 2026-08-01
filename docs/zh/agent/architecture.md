---
title: Agent 架构与核心组件
description: 深入理解 Agent 定义、Runner、Store、Worker、Registry、事件和任务规划组件的职责边界。
---

# Agent 架构与核心组件

<div v-pre>

## 设计目标

Agent 模块需要同时满足两类需求：

- 简单请求可以像普通工具 Agent 一样同步运行；
- 长任务可以跨请求、跨线程甚至跨进程暂停和恢复。

因此，运行时将“不可变能力”“可变状态”“执行逻辑”“运行时对象解析”“后台调度”分开。核心原则是：

> Store 只保存可序列化状态，Registry 负责重新绑定运行时对象，Runner 只推进一个 Run。

![Agent 智能体运行时架构](../../assets/images/agent-runtime-architecture.svg)

## 定义层：Agent

`Agent` 描述一个可复用智能体的稳定能力：

```text
Agent
├── id
├── version
├── name
├── instructions
├── ChatModel
├── ChatOptions
├── Tools
├── ToolInterceptors
├── AgentExecutionPolicy
├── ToolApprovalPolicy
├── AgentExecutionMode
├── AgentContextPolicy
├── AgentContextManager
├── ToolResultOffloadPolicy
├── AgentMiddleware（零个或多个）
├── attributes
└── AgentTaskPlanner（可选）
```

它不包含当前消息、当前工具调用和最终回答。这样同一个 Agent 可以同时服务多个 AgentRun。

## 状态层：AgentRun 与 Snapshot

`AgentRun` 是内存中的可变执行状态，`AgentRunSnapshot` 是持久化边界：

| AgentRun 内容 | 为什么需要持久化 |
| --- | --- |
| 完整消息历史 | 恢复后模型继续看到原上下文 |
| `AgentRunStatus` | 判断可运行、阻塞或终止 |
| `AgentRunPhase` | 恢复到 MODEL 或 TOOLS |
| pending ToolCalls | 防止重启后重新调用模型作决定 |
| pending ToolReferences | 按模型决策时冻结的工具身份和 metadata 恢复实现 |
| Tool 审批结果 | 恢复后不重复询问审批 |
| Token 和工具次数 | 预算跨进程累计 |
| retryCount / nextRunAt | Worker 到期后继续重试 |
| parentRunId / rootRunId | 恢复父子 Agent 关系 |
| version | 防止并发覆盖新状态 |
| agentVersion | 恢复时绑定创建任务所用的 Agent 配置版本 |
| executionModeId / version | 校验恢复前后运行模式实现没有漂移 |
| executionPolicy | 保存本次 Run 实际采用的迭代、重试和预算 |
| stepCount | 跨进程累计运行模式推进次数并强制执行 maxSteps |
| modeState | 保存自定义模式的可序列化中间状态 |

`AgentInvocationContext` 不在表中，因为它是一次调用附加的非持久化上下文。租户、用户、请求 ID
以及进程内服务对象可以通过它进入 Middleware 和 Tool；任务恢复后由当前请求或 Worker 重新附加，
不会把连接、客户端等运行时对象写入 Snapshot。

`AgentRun` 不应直接被 Java 序列化后长期保存，因为其中引用了 ChatModel、Tool 等运行时对象。正确做法是保存 Snapshot。

## 执行层：AgentRunner

`AgentRunner` 负责公共生命周期，`AgentExecutionMode` 决定一次 step 的具体推进逻辑。Runner 负责：

1. 调用模型；
2. 校验模型响应；
3. 保存模型产生的 ToolCall；
4. 解析并执行工具；
5. 写入 ToolMessage；
6. 保存 Checkpoint；
7. 处理暂停、恢复、重试和预算；
8. 产生 Listener 回调和持久化事件。

Runner 还负责把 `AgentMiddleware` 组合为步骤、模型和工具三条调用链，并通过
`AgentRuntimeEventStream` 发布模型增量、工具进度等实时事件。上下文压缩和大型结果外置由独立策略
完成，Runner 只负责在稳定边界调用它们并保存处理后的状态。

默认模式为 `ToolCallingAgentExecutionMode`。自定义模式通过 `AgentExecutionContext` 使用 Checkpoint、暂停、完成和失败等受控操作，因此仍然遵守 Store、Lease、恢复和事件语义。

自定义模式暂停时直接返回 `context.suspend(...)`。该方法会保存等待状态并返回 `AgentStepType.BLOCKED`，避免模式实现自行拼装不一致的 step 结果。

### 相邻模块协作

- `AgentWorker` 负责定时扫描和领取可运行任务；
- 工作流模块负责跨系统流程、并行分支和流程节点编排；
- `AgentRegistry` 与 `AgentToolRegistry` 负责绑定 ChatModel、Tool 等运行时对象；
- `AgentTaskPlanner` 与 `AgentPlanExecutor` 负责生成和推进任务计划。

## Store 层：AgentRunStore

```java
public interface AgentRunStore {
    AgentRunSnapshot load(String runId);

    AgentRunSnapshot save(
        AgentRunSnapshot snapshot,
        long expectedVersion
    );

    boolean requestCancellation(String runId);

    boolean isCancellationRequested(String runId);

    ParentChildRunSnapshots saveParentAndChild(...);

    List<AgentRunSnapshot> claimRunnable(...);

    AgentRunSnapshot renewLease(...);

    void releaseLease(...);
}
```

生产 Store 必须保证：

- `save` 使用 CAS 或条件更新；
- 取消标记是独立于 Lease 的单调信号，后续保存不能把它从 true 覆盖回 false；
- `saveParentAndChild` 在同一事务中保存父等待状态和子 Run；
- `claimRunnable` 原子判断可执行条件并写入 Lease；
- 同一个 Run 在有效租约期间只能被一个 Worker 领取。

`InMemoryAgentRunStore` 适合测试和单进程开发，不适合多实例生产部署。

## Registry 层

### AgentRegistry

Snapshot 保存 `agentId + agentVersion`。恢复时：

```java
AgentRunSnapshot snapshot = runStore.load(runId);
Agent agent = agentRegistry.resolve(
    snapshot.getAgentId(),
    snapshot.getAgentVersion()
);
AgentRun run = AgentRun.fromSnapshot(agent, snapshot);
```

生产实现可以从 Spring Bean、依赖注入容器或版本化配置中心解析 Agent。同一个逻辑 ID 可以同时保留多个已发布版本，存量 Run 恢复时继续使用创建时绑定的配置版本。

### AgentToolRegistry

模型产生 ToolCall 后，Runner 为实际绑定的 Tool 创建 `AgentToolReference`，并按 ToolCall ID 保存到 Snapshot：

```text
Tool
  → AgentToolRegistry.register(...)
  → 返回 AgentToolReference
  → pendingToolReferences[toolCallId]
  → Checkpoint
```

Reference 保存 `agentId`、`agentVersion`、`toolName`、可选的 `bindingId`、`bindingVersion`，以及 Tool metadata 的不可变副本。恢复到 TOOLS 阶段时：

```text
AgentToolReference → AgentToolRegistry.resolve(reference) → Tool
```

`register(...)` 和 `resolve(reference)` 构成完整契约。默认注册表使用包含 Agent 身份、版本、工具名和 metadata 的 Reference 作为键；MCP、远程工具目录和独立版本工具可以增加 binding 字段，按持久化身份精确重建代理。

::: warning 稳定名称
Reference 中只能保存 Store 支持序列化的非敏感数据。API Key、客户端、连接、Bean 和 Lambda 必须由 Registry 在当前进程中重新绑定。
:::

## 调度层：AgentWorker

Worker 不保存自己的任务队列。它从共享 Store 中领取：

```java
AgentWorker worker = new AgentWorker("worker-a", runner, 30_000);
List<AgentRun> processed = worker.pollAndRun(10);
```

或者持续轮询：

```java
worker.startPolling(1_000, 10);
```

每个领取动作会产生 Lease。Worker 完成、阻塞或失败后释放 Lease；如果 Worker 崩溃，其他 Worker 可在 Lease 到期后接管。

外部恢复命令先写入 `AgentRunCommandStore`。Worker 每次轮询会先领取命令，将审批、用户输入或子任务
完成通知幂等应用到 Run，再从 RunStore 领取已经变为可执行状态的任务。事件唤醒只用于降低延迟，
持久化 Command Inbox 才是事实来源。

## 任务规划层

任务规划没有放入 AgentRunner，因为“执行一个模型工具循环”和“协调多个业务任务”是不同职责。

```text
AgentTaskPlanner
  → AgentTaskPlanSnapshot
  → AgentPlanExecutor
  → child AgentRun
  → AgentRunner
```

任务状态通过 `AgentTaskStore` 保存，具体模型和工具执行仍由 AgentRun 承担。

## 三层事件机制

两者都能观察运行，但用途不同：

| 机制 | 特点 | 适用场景 |
| --- | --- | --- |
| `AgentListener` | 当前进程同步、低频生命周期回调 | 简单日志、指标、兼容现有监听代码 |
| `AgentRuntimeEventStream` | 当前进程细粒度实时事件，支持模型 delta 和工具进度 | WebSocket/SSE、交互式 UI、实时追踪 |
| `AgentRunEventStore` | 追加式持久化，带 sequence | 审计、异步消费、断点续读 |

`AgentRunEventEnricher` 可以把账号、租户、接口、所属模块、配置版本等平台字段附加到每一条持久化事件中。

## 平台控制面

模式说明、适用场景、参数 Schema、配置历史、发布审批、效果报告和推荐规则属于平台控制面。核心框架通过以下契约承接平台生成的有效配置：

```text
平台配置版本
  → Agent.version / attributes
  → AgentExecutionMode
  → AgentExecutionPolicy
  → AgentContextPolicy
  → AgentRunOptions
  → AgentRunner
```

这样平台可以独立演进数据模型和管理界面，而 Runner 只负责执行已经解析并冻结的运行配置。

## 依赖组合

本地开发可以直接使用默认构造器：

```java
AgentRunner runner = new AgentRunner();
```

生产环境应显式注入共享实现：

```java
AgentRunner runner = AgentRunner.builder()
    .runStore(databaseRunStore)
    .agentRegistry(applicationAgentRegistry)
    .toolRegistry(applicationToolRegistry)
    .eventStore(databaseEventStore)
    .commandStore(databaseCommandStore)
    .artifactStore(objectArtifactStore)
    .build();
```

任务规划再组合：

```java
AgentPlanExecutor planExecutor = new AgentPlanExecutor(
    runner,
    databaseTaskStore
);
```

## 下一步

- [Agent 与 AgentRun](./agent-and-run.md)
- [执行循环与状态](./execution-lifecycle.md)
- [Checkpoint 与中断恢复](./checkpoint-resume.md)
- [平台集成与扩展](./platform-integration.md)

</div>
