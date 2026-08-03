---
title: AgentRun
description: 理解单次 Agent 运行的状态、阶段、消息、计数、元数据和 Conversation 关系。
---

# AgentRun

## 概述

`AgentRun` 表示一个具体任务的可变运行状态。它包含消息历史、当前状态与阶段、待执行 ToolCall、暂停信息、预算计数、任务计划以及最终结果。Run 由 `AgentRunner` 创建和推进，调用方主要负责读取状态、附加调用上下文以及提交恢复命令。

## Run 与 Conversation

一个 Run 对应一个任务或一轮对话；一个 `AgentConversation` 可以包含多个先后发生的 Run，并共享 `ChatMemory`。

```java
AgentConversation conversation = AgentConversation.create("session-1", agent);
AgentRun first = runner.run(conversation, "我叫小明");
AgentRun second = runner.run(conversation, "我叫什么？");
```

独立 Run 也可接受业务系统加载的历史：

```java
AgentRun run = runner.run(agent, history, new UserMessage("继续"));
```

历史里的 `SystemMessage` 会被忽略，系统指令始终以当前 Agent 定义为准。

## 状态

| 状态 | 含义 |
| --- | --- |
| `READY` | 已创建，等待推进或 Worker 领取 |
| `RUNNING` | 正在执行 |
| `WAITING_FOR_USER` | 等待用户补充输入 |
| `WAITING_FOR_APPROVAL` | 等待工具审批 |
| `WAITING_FOR_CHILD` | 等待子 Run |
| `RETRY_SCHEDULED` | 等待 `nextRunAt` 到期 |
| `COMPLETED` | 正常完成 |
| `FAILED`、`CANCELLED` | 失败或取消 |
| `MAX_ITERATIONS_REACHED` | 模型调用达到上限 |
| `MAX_STEPS_REACHED` | Runner 总推进次数达到上限 |
| `BUDGET_EXCEEDED` | 预算达到上限 |

使用 `status.isBlocked()` 和 `status.isTerminal()` 判断状态类别，不要自行维护不完整的枚举集合。

## 阶段

`AgentRunPhase.MODEL` 表示下一步应请求模型；`TOOLS` 表示已保存模型产生的 ToolCall，下一步应审批或执行工具。重试和审批恢复会回到快照记录的阶段。

状态回答“Run 能否继续”，阶段回答“继续时做什么”，两者不能混用。

## 读取结果与进度

```java
AgentRunStatus status = run.getStatus();
String output = run.getFinalOutput();
Throwable error = run.getError();

int iterations = run.getIterationCount();
int steps = run.getStepCount();
int toolCalls = run.getToolCallCount();
long totalTokens = run.getTotalTokens();
```

只有正常完成时 `getFinalOutput()` 才是最终答案。失败恢复自 Snapshot 后，异常会以恢复异常表示，类型名与消息保存在快照中，不能依赖原异常对象仍然存在。

## 消息与多模态输入

Runner 支持 `UserMessage` 结构化输入，消息可以携带文本和多模态内容。创建 Run 时会复制用户消息，避免调用方后续修改影响 Checkpoint。

```java
AgentRun run = runner.run(agent, userMessage);
List<Message> history = run.getConversationHistory();
```

`getConversationHistory()` 返回排除系统消息后的副本，适合保存到业务会话表；`getPrompt()` 暴露运行 Prompt，通常只应由扩展组件读取。

## 元数据与调用上下文

```java
AgentRunOptions options = AgentRunOptions.builder()
    .metadata("businessOrderId", "O-1001")
    .invocationContext(AgentInvocationContext.builder()
        .tenantId("tenant-a")
        .userId("u-1")
        .requestId("req-1")
        .build())
    .build();
```

`metadata` 必须可序列化，会进入 Snapshot；`AgentInvocationContext` 是瞬时运行依赖，不进入 Snapshot，恢复或 Worker 执行时必须重新附加。密码、Token、数据库连接和 Spring Bean 不应放入 metadata。

## 父子关系与计划

子 Run 通过 `parentRunId` 指向直接父任务，通过 `rootRunId` 关联整棵任务树。根 Run 的 `rootRunId` 等于自身 ID。开启规划时，可用 `getTaskPlan()` 或 `getTaskProgress()` 查询不可变进度视图。

## 快照

```java
AgentRunSnapshot snapshot = run.toSnapshot();
```

快照是隔离副本，包含恢复所需状态但不包含模型、工具和瞬时调用上下文。正常业务代码应通过 `runner.checkpoint(run)` 保存，由 Store 分配版本，而不是只调用 `toSnapshot()` 后自行覆盖数据。
