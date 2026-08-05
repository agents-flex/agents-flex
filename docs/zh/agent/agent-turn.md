---
title: AgentTurn
description: 理解单次 Agent 轮次的边界、状态、阶段、消息、计数和父子关系。
---

# AgentTurn

## 概述

`AgentTurn` 表示某个 Agent 从接收一次输入到产生最终结果的完整轮次。一个 Turn 可以包含多次模型
迭代、工具调用、暂停恢复和自动重试。根 Turn 的输入通常来自用户，子 Turn 的输入来自父 Agent 的
任务委派；父 Agent 每次调用子 Agent 都会创建一个独立 Turn。

Turn 包含消息历史、当前状态与阶段、待执行 ToolCall、暂停信息、预算计数、任务计划以及最终结果。
它由 `AgentRunner` 创建和推进，调用方主要负责读取状态、提供可持久化业务元数据以及提交恢复命令。

## Turn 与业务会话

一个根 Turn 通常对应业务会话中的一轮用户交互；子 Turn 属于该轮内部的 Agent 委派，不会自动成为
新的用户对话轮次。业务系统仍负责维护 conversationId、`ChatMemory` 和当前未结束的 turnId。
Framework 不提供新的 Conversation 容器；需要融合时只在 Runner 上配置可选的 ChatMemory
Provider：

```java
AgentRunner runner = AgentRunner.builder()
    .chatMemoryProvider(id -> loadMemory(id))
    .build();

AgentTurn turn = runner.run(agentId, "conversation-1001", new UserMessage("继续"));
```

绑定的 conversationId 会作为 Turn metadata 随 Snapshot 恢复。Runner 按 `maxAttachedMessages` 分页读取
最近的模型可见历史并投影根 Turn 新增的消息，不会把完整业务会话复制进 Turn，也不会替业务系统创建会话、
选择当前 Turn 或清理历史。传入历史里的 `SystemMessage` 会被忽略，系统指令始终以当前 Agent 定义为准。

不使用融合模式时，仍可调用 `runner.run(agent, history, userMessage)`，并在完成后读取
`turn.getConversationHistory()` 自行回写。

## 状态

| 状态 | 含义 |
| --- | --- |
| `READY` | 已创建，等待推进或 Worker 领取 |
| `RUNNING` | 正在执行 |
| `WAITING_FOR_USER` | 等待用户补充输入 |
| `WAITING_FOR_APPROVAL` | 等待工具审批 |
| `WAITING_FOR_CHILD` | 等待子 Turn |
| `RETRY_SCHEDULED` | 等待 `nextRunnableAt` 到期 |
| `COMPLETED` | 正常完成 |
| `FAILED`、`CANCELLED` | 失败或取消 |
| `MAX_ITERATIONS_REACHED` | 模型调用达到上限 |
| `MAX_STEPS_REACHED` | Runner 总推进次数达到上限 |
| `BUDGET_EXCEEDED` | 预算达到上限 |

使用 `status.isBlocked()` 和 `status.isTerminal()` 判断状态类别，不要自行维护不完整的枚举集合。

## 阶段

`AgentTurnPhase.MODEL` 表示下一步应请求模型；`TOOLS` 表示已保存模型产生的 ToolCall，下一步应审批或执行工具。重试和审批恢复会回到快照记录的阶段。

状态回答“Turn 能否继续”，阶段回答“继续时做什么”，两者不能混用。

## 读取结果与进度

```java
AgentTurnStatus status = turn.getStatus();
String output = turn.getFinalOutput();
Throwable error = turn.getError();

int iterations = turn.getIterationCount();
int steps = turn.getStepCount();
int toolCalls = turn.getToolCallCount();
long totalTokens = turn.getTotalTokens();
```

只有正常完成时 `getFinalOutput()` 才是最终答案。失败恢复自 Snapshot 后，异常会以恢复异常表示，类型名与消息保存在快照中，不能依赖原异常对象仍然存在。

## 消息与多模态输入

Runner 支持 `UserMessage` 结构化输入，消息可以携带文本和多模态内容。创建 Turn 时会复制用户消息，避免调用方后续修改影响 Snapshot。

```java
AgentTurn turn = runner.run(agent, userMessage);
List<Message> history = turn.getConversationHistory();
```

`getConversationHistory()` 返回排除系统消息后的模型协议消息副本，适合手工集成会话存储；
`getConversationId()` 返回可选融合模式绑定的业务会话 ID。`getPrompt()` 暴露运行 Prompt，通常只应由
扩展组件读取。

## 元数据与流式调用

```java
AgentTurnOptions options = AgentTurnOptions.builder()
    .metadata("businessOrderId", "O-1001")
    .metadata("tenantId", "tenant-a")
    .metadata("userId", "u-1")
    .streaming(true)
    .build();
```

`metadata` 用于业务订单号、租户 ID、用户 ID 等恢复后仍需使用的信息。值必须可序列化，并会进入 Snapshot。密码、Token、数据库连接和 Spring Bean 不应放入 metadata，运行期服务应由 Middleware 或 Tool 自身通过依赖注入等方式持有。

`streaming(true)` 只控制当前进程内这次 Turn 的模型调用方式，不进入 Snapshot。同一进程创建的子 Turn 会继承该设置；从 Snapshot 恢复或由 Worker 执行时默认使用非流式调用。

## 父子关系与计划

子 Turn 通过 `parentTurnId` 指向直接父任务，通过 `rootTurnId` 关联整棵任务树。根 Turn 的 `rootTurnId` 等于自身 ID。开启规划时，可用 `getTaskPlan()` 或 `getTaskProgress()` 查询不可变进度视图。

## 快照

```java
AgentTurnSnapshot snapshot = turn.toSnapshot();
```

快照是隔离副本，包含恢复所需状态，但不包含模型、工具和当前进程的 streaming 设置。正常业务代码应通过 `runner.saveSnapshot(turn)` 持久化，由 Store 分配版本，而不是只调用 `toSnapshot()` 后自行覆盖数据。
