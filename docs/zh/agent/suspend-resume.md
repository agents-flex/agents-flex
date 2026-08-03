---
title: 挂起和恢复
description: 建立可持久化暂停点，使用结构化命令同步恢复或通过可靠收件箱异步恢复。
---

# 挂起和恢复

## 概述

Agent 遇到人工审批、缺少用户信息、等待子任务或延迟重试时，不应占用线程等待。Runner 把等待原因保存为 `AgentSuspension`，将 Run 转换为阻塞状态并写入 Checkpoint。外部事件到达后，通过 `AgentResumeCommand` 从记录的阶段继续。

## 暂停类型

| Suspension | Run 状态 | 典型恢复命令 |
| --- | --- | --- |
| `USER_INPUT` | `WAITING_FOR_USER` | `userInput(content)` |
| `TOOL_APPROVAL` | `WAITING_FOR_APPROVAL` | `approveTool` / `rejectTool` |
| `CHILD_AGENT` | `WAITING_FOR_CHILD` | `childCompleted`，通常由 Runner 内部处理 |
| `RETRY` | `RETRY_SCHEDULED` | `retry()` 或 Worker 到期领取 |

Suspension 还保存 correlationId、展示消息、恢复 phase 和可序列化 metadata。

## 主动挂起

需要由业务控制面显式建立等待点时，可以使用：

```java
AgentRun blocked = runner.suspend(
    run,
    AgentSuspension.userInput("请提供订单号"));
```

Runner 会保存 Checkpoint、发布暂停事件并返回 `BLOCKED`。普通业务代码不应只修改 Run 状态而跳过这些步骤。

## 同步恢复

```java
AgentRun resumed = runner.resume(
    runId,
    AgentResumeCommand.userInput("O-1001")
        .withMetadata("source", "web"));
```

审批示例：

```java
AgentRun resumed = runner.resume(runId,
    AgentResumeCommand.approveTool(callId)
        .withMetadata("approverId", "u-100"));
```

Runner 会校验当前 Suspension 类型和 correlationId。批准后执行已保存的原 ToolCall；拒绝后写入与原调用关联的工具结果，供模型解释，不会执行函数体。

## Command Inbox

审批回调与 Worker 不在同一服务时，先提交持久化命令：

```java
runner.submitCommand(
    "approval-event-7788",
    runId,
    AgentResumeCommand.approveTool(callId));
```

`AgentRunCommandStore` 按 commandId 幂等保存，Worker 使用租约领取。命令状态经历 `PENDING -> CLAIMED -> COMPLETED`，也可能释放回 PENDING 或最终 `FAILED`。

入箱成功只表示命令可靠保存，不表示 Run 已经恢复完成。外部 API 应返回命令 ID，供调用方查询处理状态。

## 唤醒调度器

```java
runner.addWakeupListener(command -> queue.signal(command.getRunId()));
```

Wakeup Listener 在命令成功入箱后通知外部调度系统，可降低轮询延迟。可靠性仍由 Command Store 保证；通知丢失时 Worker 的常规轮询必须最终处理命令。

## 幂等与竞态

- 外部事件使用稳定 commandId，重复回调提交同一命令。
- 工具审批 correlationId 必须匹配当前待处理 ToolCall。
- Store 版本冲突时重新加载状态，不能覆盖较新快照。
- Run 已终止后到达的命令应成为明确失败或幂等完成，不能重新打开终态。
- 多个审批人竞争时，应由业务审批系统先决定唯一结果。

## 用户体验

暂停接口应向前端返回 Run ID、状态、Suspension message、correlationId 和必要的安全元数据。不要直接展示模型原始内部内容或敏感工具参数。恢复 API 应鉴权，并把审批人、渠道、理由写入命令 metadata 和审计事件。

## 不应做的事

不要用 `Thread.sleep` 等待审批或重试，不要在恢复时创建一个全新 Run，也不要让模型重新生成待审批参数。暂停点和原 ToolCall 已在 Checkpoint 中，正确操作是恢复原 Run。
