---
title: 会话、消息与 AgentRun
description: 理解每条消息如何形成独立 AgentRun，以及 Conversation、状态、阶段和 Snapshot 的关系。
---

# 会话、消息与 AgentRun

## 概述

`AgentRun` 表示一条用户消息触发的一次独立运行。它保存模型与工具已经执行到哪里、当前是否等待外部事件、消耗了多少预算，以及最终结果或错误。

即使用户只说“你好”，Runner 也会创建一个 Run；如果用户随后要求分析图片，则创建第二个 Run。`AgentConversation` 让第二个 Run 看到之前的消息，但不会把两次运行合并成一个状态对象。

## 快速开发

一次性任务：

```java
AgentRun run = runner.run(agent, "分析今天的库存异常");
```

持续对话：

```java
AgentConversation conversation = AgentConversation.create("conversation-1001", agent);

AgentRun greeting = runner.run(conversation, "你好");
AgentRun analysis = runner.run(conversation, "继续分析刚才提到的库存问题");
```

读取结果时先判断状态：

```java
if (run.getStatus().isTerminal()) {
    System.out.println(run.getFinalOutput());
} else if (run.getStatus().isBlocked()) {
    System.out.println(run.getSuspension().getMessage());
}
```

## Run 中保存什么

`AgentRun` 包含以下几类状态：

- 身份：runId、agentId/version、parentRunId、rootRunId；
- 对话：用户、模型、ToolCall 和 ToolMessage；
- 执行：status、phase、pendingToolCalls、iterationCount、stepCount；
- 等待：`AgentSuspension`、nextRunAt、工具审批结果；
- 资源：输入、输出、总 Token、工具调用次数和预算终止原因；
- 调度：Checkpoint version、leaseOwner、leaseId、leaseUntil；
- 规划：任务计划、当前任务、子 Run 和规划深度；
- 输出：finalMessage 或 error；
- 扩展：持久化 metadata、modeState，以及非持久化 Invocation Context。

这些字段共同决定恢复位置，因此不能只保存聊天消息。

## 状态与阶段

`AgentRunStatus` 面向调用方描述生命周期：

| 分类 | 状态 |
| --- | --- |
| 可推进 | `READY`、`RUNNING` |
| 等待 | `WAITING_FOR_USER`、`WAITING_FOR_APPROVAL`、`WAITING_FOR_CHILD`、`RETRY_SCHEDULED` |
| 终止 | `COMPLETED`、`FAILED`、`CANCELLED`、`MAX_ITERATIONS_REACHED`、`MAX_STEPS_REACHED`、`BUDGET_EXCEEDED` |

`AgentRunPhase` 是 Runner 的恢复游标。默认模式主要在 `MODEL` 和 `TOOLS` 之间切换：等待审批时状态是 `WAITING_FOR_APPROVAL`，phase 仍是 `TOOLS`，表示批准后继续执行已保存的工具调用。

## Suspension 是等待说明

进入等待状态时，Run 同时保存 `AgentSuspension`：

```java
AgentSuspension suspension = run.getSuspension();
System.out.println(suspension.getType());
System.out.println(suspension.getCorrelationId());
System.out.println(suspension.getMessage());
```

correlationId 把审批绑定到具体 ToolCall、把父 Run 绑定到具体子 Run，防止迟到的外部事件恢复错误任务。

## Conversation 与 Memory

`AgentConversation` 绑定一个 Agent 和一个 `ChatMemory`。Run 正常完成后，Conversation 释放 activeRunId，下一条消息可以开始；Run 阻塞时 activeRunId 保留，此时审批或补充输入必须使用 `runner.resume(conversation, command)`，不能当作新一轮普通消息。

```java
AgentConversation conversation = AgentConversation.of(
    conversationId,
    agent,
    persistentChatMemory
);
```

框架没有另设 Conversation Store，因为应用往往已经有会话表和 Memory 实现。需要跨进程恢复阻塞会话时，业务系统同时保存 conversationId、Memory 和 activeRunId，再使用 `AgentConversation.restore(...)` 重建句柄。

## 多模态输入

```java
UserMessage input = new UserMessage("检查图片和附件中的问题");
input.addImageUrl(imageUrl);
input.addFileUrl(fileUrl);
input.addAudioUrl(audioUrl);
input.addVideoUrl(videoUrl);

AgentRun run = runner.run(conversation, input);
```

Run 会复制输入消息，调用方后续修改原对象不会影响 Checkpoint。模型支持范围仍由 ChatModel 决定。

## Snapshot

`run.toSnapshot()` 返回跨进程持久化值对象。Snapshot 不包含 Agent、ChatModel、Tool、Middleware、Throwable 实例或 Invocation Context；恢复时 Runner 使用其中的 agentId/version 调用 AgentLoader，再附加当前进程的 Invocation Context。

应用不应直接调用 `AgentRun` 的内部创建或恢复工厂。统一使用 `AgentRunner.start/run/restore`，这样初始 Checkpoint、定义校验和恢复绑定不会被遗漏。

## metadata 与 modeState

Run metadata 用于任务类型、租户审计键和业务关联 ID；modeState 专门保存自定义执行模式的恢复状态。两者都会进入 Snapshot，值必须满足 Store 序列化约束。请求级服务、凭证和数据库连接不要放入其中。

