---
title: 暂停、恢复与 Command Inbox
description: 使用 AgentSuspension 和类型化恢复命令处理用户补充、审批、重试和子任务等待。
---

# 暂停、恢复与 Command Inbox

## 概述

Agent 的暂停不是挂起 Java 线程，而是保存一个等待状态并结束当前调用。几秒后的自动重试和几天后的人工审批都使用同一模型：Snapshot 记录等待什么、恢复到哪里，外部事件用类型化命令解除等待。

## 快速开发

显式等待用户补充：

```java
AgentRun waiting = runner.suspend(
    run,
    AgentSuspension.userInput("请补充订单号")
);

AgentRun continued = runner.resume(
    waiting.getId(),
    AgentResumeCommand.userInput("订单号是 A1024")
);
```

`resume` 应用命令后立即推进到下一个阻塞或终止状态。

## Suspension 保存什么

`AgentSuspension` 包含：

- type：等待用户、审批、子 Agent 或重试；
- correlationId：ToolCall ID 或 childRunId；
- message：面向用户或运营人员的说明；
- resumePhase：解除等待后继续 MODEL 还是 TOOLS；
- metadata：审批原因、重试时间等可持久化信息。

Run 的 status 负责告诉外部系统“当前在等什么”，phase 负责告诉 Runner“恢复后从哪里继续”。

## 类型化命令

| 工厂方法 | 用途 |
| --- | --- |
| `continueRun()` | 解除通用等待 |
| `userInput(content)` | 添加用户补充并继续模型阶段 |
| `approveTool(callId)` | 批准当前工具调用 |
| `rejectTool(callId, reason)` | 拒绝当前工具调用 |
| `retry()` | 执行已到期重试 |
| `childCompleted(childRunId)` | 通知父 Run 子任务已结束 |

Runner 会校验命令和 Suspension 是否匹配。审批命令不能恢复等待用户输入的 Run，错误 callId 也不会被接受。

## 同步恢复与异步恢复

同步接口适合同一个请求内完成的短等待：

```java
AgentRun result = runner.resume(runId, command);
```

只解除等待但不继续执行：

```java
AgentRun runnable = runner.submitResume(runId, command);
```

跨进程或审批系统应使用持久化 Inbox：

```java
AgentRunCommand accepted = runner.submitCommand(
    commandId,
    runId,
    command
);
```

`commandId` 是业务幂等键。相同 ID 和相同内容重复提交返回已有命令；相同 ID 绑定不同决定会被拒绝。

## Worker 如何消费命令

`AgentWorker.pollAndRun` 会先领取命令，再领取可运行 Run。Command Store 为命令维护 PENDING、CLAIMED、COMPLETED 和 FAILED 状态，以及独立 Lease 和 attempts。命令成功应用到 Run 后才 acknowledge；进程中途退出时可以再次领取。

Runner 同时把已处理 commandId 记录到 Run metadata，覆盖“Run 已保存、Command 尚未 ack”这一故障窗口，避免同一命令重复改变状态。

## 事件唤醒与轮询兜底

```java
runner.addWakeupListener(command ->
    messageQueue.publish(command.getRunId()));
```

Wakeup Listener 在命令成功入箱后触发，可以通知 Worker 立即轮询。通知不是可靠事实来源：消息可能丢失，因此 Worker 仍应周期性扫描 Command Store；真正的恢复决定以持久化 Inbox 为准。

## 持续对话中的等待

Conversation 存在 activeRunId 时，普通新消息会被拒绝。审批或用户补充应恢复原 Run：

```java
runner.resume(conversation, AgentResumeCommand.userInput("A1024"));
```

只有原 Run 到达终止状态，Conversation 才释放 activeRunId，下一条普通消息才创建新 Run。

