---
title: Snapshot
description: 理解 AgentRunSnapshot 的内容、保存边界、版本控制和恢复兼容性。
---

# Snapshot

## 概述

Snapshot 是 `AgentRun` 在稳定执行边界的持久化状态，具体值对象为 `AgentRunSnapshot`。它让任务在审批回调、后台 Worker、进程重启或节点切换后继续执行，而无需重放整条模型与工具链。

Snapshot 保存“继续执行所需的数据”，而不是把整个 Java 对象图序列化下来。模型、工具、Middleware 及其运行期服务由 `AgentLoader` 重新装配。

运行状态分为三层：

- `AgentRunState`：唯一的可序列化状态定义，集中保存生命周期、计数、租约、规划和消息副本。
- `AgentRun`：可变 State 加上 Agent、Prompt、Throwable 和当前进程的运行时属性。
- `AgentRunSnapshot`：Agent ID/版本加上深拷贝后的不可变 State。

因此 Run 与 Snapshot 不再各自镜像全部字段；新增持久化状态时只需在 `AgentRunState` 定义一次。
`AgentRunSnapshot` 只暴露 Agent 标识和 `getState()`，不再提供运行字段的转发 getter 或 Builder。

## 保存内容

快照包含：

- Run ID、Agent ID/版本。
- status、phase、消息与待处理 ToolCall。
- Suspension、工具审批决定和任务计划。
- 模型迭代、Runner step、Token、工具、重试等计数。
- 预算终止原因、nextRunAt、取消标记。
- 父/根 Run ID、planning depth。
- metadata、最终消息和错误摘要。
- Store version 与 Worker Lease 信息。

不包含 `ChatModel`、Tool 实例、Listener、Middleware、原始异常对象和当前进程的 streaming 设置。

## 自动保存边界

Runner 会在关键状态变化后保存，例如：

- Run 创建与开始。
- 模型已产生待执行 ToolCall。
- 工具完成并写入 ToolMessage。
- 上下文压缩。
- 暂停、恢复和安排重试。
- 计划与任务状态变化。
- 完成、失败、取消或预算终止。

“先保存 ToolCall，再请求审批或执行”保证恢复后使用的是原始参数。

## 手动 Snapshot

```java
AgentRunSnapshot saved = runner.saveSnapshot(run);
AgentRunState state = saved.getState();
System.out.println(state.getVersion());
System.out.println(state.getStatus());
```

`run.toSnapshot()` 只生成内存副本，不执行 Store 乐观写入、事件发布或本地版本同步。需要显式持久化稳定边界时调用 `runner.saveSnapshot(run)`。

Store 或测试需要构造、修改持久化状态时，应通过 State 完成字段级操作：

```java
AgentRunState state = AgentRunState.builder(runId, executionPolicy, createdAt)
    .status(AgentRunStatus.READY)
    .build();
AgentRunSnapshot snapshot = AgentRunSnapshot.of(agentId, agentVersion, state);

AgentRunSnapshot updated = snapshot.withState(
    snapshot.getState().toBuilder()
        .status(AgentRunStatus.RUNNING)
        .build());
```

## 乐观版本

`AgentRunStore.save(snapshot, expectedVersion)` 必须比较当前版本。创建使用约定的初始期望版本，后续保存只有版本匹配才能成功，成功后 Store 分配递增版本。冲突时抛出 `AgentRunVersionConflictException`。

发生冲突说明另一个执行者已经提交新状态。不能用旧快照覆盖；应停止当前推进并重新加载，必要时由业务决定是否重试命令。

## 恢复校验

```java
AgentRun run = runner.restore(runId);
```

恢复时 Runner：

1. 从 RunStore 读取快照。
2. 调用 `AgentLoader.load(agentId, agentVersion)`。
3. 从不可变 State 创建可变运行副本，并重建 Prompt 和异常摘要。
4. 使用恢复后的非流式默认调用方式继续推进；业务 metadata 已包含在 Snapshot 中。

历史 Agent 版本缺失时应明确失败，不能静默使用最新定义。

## 序列化

模块提供 `AgentStoreSerializer` 与默认 `FastjsonAgentStoreSerializer`。默认格式是带类型信息的 JSONB 二进制，并限制可反序列化包前缀：

```java
AgentStoreSerializer serializer =
    new FastjsonAgentStoreSerializer("com.example.agent.state.");
```

只有在 metadata 确实需要业务值对象时才加入精确白名单。不要接受任意 AutoType，也不要把非 Serializable 对象写入状态。

`AgentRunState` 引入后，Snapshot 的序列化结构由平铺字段调整为嵌套 State，Java 序列化版本也已提升。已有部署升级前应先完成旧 Run，或由业务迁移旧 Snapshot；不要在滚动升级期间让新旧 Runner 共同读写同一批未完成 Run。

## 数据保留

终态 Run 的 Snapshot 仍是审计、结果查询和父子树展示的依据。删除策略应与事件、Command 和 Artifact 协调：不能先删除 Artifact 却保留引用，也不能在父 Run 仍等待时删除子 Run。
