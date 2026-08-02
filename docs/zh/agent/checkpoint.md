---
title: Checkpoint
description: 理解 AgentRunSnapshot 的内容、保存边界、版本控制和恢复兼容性。
---

# Checkpoint

## 概述

Checkpoint 是 `AgentRun` 在稳定执行边界的持久化状态，具体值对象为 `AgentRunSnapshot`。它让任务在审批回调、后台 Worker、进程重启或节点切换后继续执行，而无需重放整条模型与工具链。

Checkpoint 保存“继续执行所需的数据”，而不是把整个 Java 对象图序列化下来。模型、工具、Middleware 和调用期服务由 AgentLoader 与 InvocationContextProvider 恢复。

## 保存内容

快照包含：

- Run ID、Agent ID/版本、执行模式 ID/版本。
- status、phase、消息与待处理 ToolCall。
- Suspension、工具审批决定和任务计划。
- 迭代、step、Token、工具、重试等计数。
- 预算终止原因、nextRunAt、取消标记。
- 父/根 Run ID、planning depth。
- metadata、modeState、最终消息和错误摘要。
- Store version 与 Worker Lease 信息。

不包含 `ChatModel`、Tool 实例、Listener、Middleware、原始异常对象和 `AgentInvocationContext`。

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

## 手动 Checkpoint

```java
AgentRunSnapshot saved = runner.checkpoint(run);
System.out.println(saved.getVersion());
```

自定义模式应通过 `AgentExecutionContext.checkpoint()` 或 `checkpointAndContinue()` 保存 Mode State。`run.toSnapshot()` 只生成副本，不执行 Store 乐观写入、事件发布或本地版本同步。

## 乐观版本

`AgentRunStore.save(snapshot, expectedVersion)` 必须比较当前版本。创建使用约定的初始期望版本，后续保存只有版本匹配才能成功，成功后 Store 分配递增版本。冲突时抛出 `AgentRunVersionConflictException`。

发生冲突说明另一个执行者已经提交新状态。不能用旧快照覆盖；应停止当前推进并重新加载，必要时由业务决定是否重试命令。

## 恢复校验

```java
AgentRun run = runner.restore(runId, invocationContext);
```

恢复时 Runner：

1. 从 RunStore 读取快照。
2. 调用 `AgentLoader.load(agentId, agentVersion)`。
3. 校验执行模式 ID 与版本。
4. 重建消息、状态、计数和计划。
5. 附加调用方提供的瞬时 Invocation Context。

历史 Agent 或模式版本缺失时应明确失败，不能静默使用最新逻辑。

## 序列化

模块提供 `AgentStoreSerializer` 与默认 `FastjsonAgentStoreSerializer`。默认格式是带类型信息的 JSONB 二进制，并限制可反序列化包前缀：

```java
AgentStoreSerializer serializer =
    new FastjsonAgentStoreSerializer("com.example.agent.state.");
```

只有在 metadata 或 modeState 确实需要业务值对象时才加入精确白名单。不要接受任意 AutoType，也不要把非 Serializable 对象写入状态。

## 模式演进

自定义 Mode State 应有稳定字段含义。改变恢复算法时升级 `AgentExecutionMode.getVersion()`，并选择：保留旧模式实现、提供迁移工具，或在发布前排空旧 Run。仅修改类代码却保持版本不变，会让历史 Checkpoint 在新语义下继续，风险更高。

## 数据保留

终态 Run 的 Checkpoint 仍是审计、结果查询和父子树展示的依据。删除策略应与事件、Command 和 Artifact 协调：不能先删除 Artifact 却保留引用，也不能在父 Run 仍等待时删除子 Run。
