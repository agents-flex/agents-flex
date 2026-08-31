---
title: Snapshot
description: 理解 AgentTurnSnapshot 的内容、保存边界、版本控制和恢复兼容性。
---

# Snapshot

## 概述

Snapshot 是 `AgentTurn` 在稳定执行边界的持久化状态，具体值对象为 `AgentTurnSnapshot`。它让任务在审批回调、后台 Worker、进程重启或节点切换后继续执行，而无需重放整条模型与工具链。

Snapshot 保存“继续执行所需的数据”，而不是把整个 Java 对象图序列化下来。模型、工具、Middleware 及其运行期服务由 `AgentLoader` 重新装配。

运行状态分为三层：

- `AgentTurnState`：唯一的可序列化状态定义，集中保存生命周期、计数、租约、规划和消息副本。
- `AgentTurn`：可变 State 加上 Agent、Prompt、Throwable 和当前进程的运行时属性。
- `AgentTurnSnapshot`：Agent ID/版本加上深拷贝后的不可变 State。

因此 Turn 与 Snapshot 不再各自镜像全部字段；新增持久化状态时只需在 `AgentTurnState` 定义一次。
`AgentTurnSnapshot` 只暴露 Agent 标识和 `getState()`，不再提供运行字段的转发 getter 或 Builder。

## 保存内容

快照包含：

- Turn ID、Agent ID/版本。
- status、phase、消息与待处理 ToolCall。
- Suspension、工具审批决定和任务计划。
- 模型迭代、Runner step、Token、工具、重试等计数。
- 预算终止原因、nextRunnableAt、取消标记。
- metadata、最终消息和错误摘要。
- Store version 与 Worker Lease 信息。

不包含 `ChatModel`、Tool 实例、Listener、Middleware 和原始异常对象；Turn 的 streaming 设置属于可恢复运行状态，会随快照保存。

## 自动保存边界

Runner 会在关键状态变化后保存，例如：

- Turn 创建与开始。
- 模型已产生待执行 ToolCall。
- 工具完成并写入 ToolMessage。
- 暂停、恢复和安排重试。
- 计划与任务状态变化。
- 完成、失败、取消或预算终止。

“先保存 ToolCall，再请求审批或执行”保证恢复后使用的是原始参数。

## 手动 Snapshot

```java
AgentTurnSnapshot saved = runner.saveSnapshot(turn);
AgentTurnState state = saved.getState();
System.out.println(state.getVersion());
System.out.println(state.getStatus());
```

`turn.toSnapshot()` 只生成内存副本，不执行 Store 乐观写入、事件发布或本地版本同步。需要显式持久化稳定边界时调用 `runner.saveSnapshot(turn)`。

Store 或测试需要构造、修改持久化状态时，应通过 State 完成字段级操作：

```java
AgentTurnState state = AgentTurnState.builder(turnId, executionPolicy, createdAt)
    .status(AgentTurnStatus.READY)
    .build();
AgentTurnSnapshot snapshot = AgentTurnSnapshot.of(agentId, agentVersion, state);

AgentTurnSnapshot updated = snapshot.withState(
    snapshot.getState().toBuilder()
        .status(AgentTurnStatus.RUNNING)
        .build());
```

## 乐观版本

`AgentTurnStore.save(snapshot, expectedVersion)` 必须比较当前版本。创建使用约定的初始期望版本，后续保存只有版本匹配才能成功，成功后 Store 分配递增版本。冲突时抛出 `AgentTurnVersionConflictException`，该异常位于 `com.agentsflex.agent.exception` 包。

发生冲突说明另一个执行者已经提交新状态。不能用旧快照覆盖；应停止当前推进并重新加载，必要时由业务决定是否重试恢复动作。

## 恢复校验

```java
AgentTurn turn = runner.restore(turnId);
```

恢复时 Runner：

1. 从 TurnStore 读取快照。
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

从 `AgentRun` 迁移到 `AgentTurn` 是破坏性持久化变更：类型名、`runId` 系列字段和事件名均已更新，
Framework 不提供旧 Snapshot 的兼容反序列化层。已有部署升级前应先完成旧 Run，或由业务离线迁移
未完成 Snapshot；不要在滚动升级期间让新旧 Runner 共同读写同一批状态。

## 数据保留

终态 Turn 的 Snapshot 仍是审计和结果查询的依据。删除策略应与业务事件协调。
