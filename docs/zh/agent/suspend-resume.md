---
title: 挂起和恢复
description: 建立可持久化暂停点，使用结构化恢复动作继续执行原 Turn。
---

# 挂起和恢复

## 概述

Agent 遇到人工审批、缺少用户信息、等待子任务或延迟重试时，不应占用线程等待。Runner 把等待原因保存为 `AgentSuspension`，将 Turn 转换为阻塞状态并写入 Snapshot。外部事件到达后，通过 `AgentResumeCommand` 从记录的阶段继续。

## 暂停类型

| Suspension | Turn 状态 | 典型恢复命令 |
| --- | --- | --- |
| `USER_INPUT` | `WAITING_FOR_USER` | `userInput(content)` 或 `userInput(callId, data)` |
| `TOOL_APPROVAL` | `WAITING_FOR_APPROVAL` | `approveTool` / `rejectTool` |
| `CHILD_AGENT` | `WAITING_FOR_CHILD` | `childCompleted`，通常由 Runner 内部处理 |
| `RETRY` | `RETRY_SCHEDULED` | `retry()` 或 Worker 到期领取 |

Suspension 还保存 correlationId、展示消息、恢复 phase 和可序列化 metadata。

## 模型请求用户输入

需要在同一个长任务中等待用户补充信息时，为 Agent 注册内置控制工具，并在指令中说明允许使用的
业务表单：

```java
Agent agent = Agent.builder("support-agent")
    .instructions("创建工单缺少必要信息时调用 request_user_input；不要猜测缺失字段。")
    .chatModel(chatModel)
    .tool(AgentUserInputTool.builder()
        .form(AgentFormDefinition.builder("support_ticket_details")
            .whenToUse("创建故障工单缺少必要信息时使用")
            .schema(supportTicketSchema)
            .build())
        .build())
    .tool(createTicketTool)
    .build();
```

模型发现信息不足时可以产生：

```json
{
  "name": "request_user_input",
  "arguments": {
    "formKey": "support_ticket_details"
  }
}
```

Runner 不执行该工具函数，而是保存原 ToolCall，使用其 ID 创建 `USER_INPUT` Suspension，并进入
`WAITING_FOR_USER`。Runner 从 Tool 中解析对应的 `AgentFormDefinition`，并将完整 JSON Schema 固化进
Suspension 和 Snapshot；模型不能生成或修改 Schema。

用户提交表单后，业务 API 应先使用 Suspension 中的 Schema 完成鉴权和字段校验，再提交结构化恢复命令：

```java
Map<String, Object> formData = new LinkedHashMap<>();
formData.put("affectedSystem", "登录系统");
formData.put("impactScope", "ALL_USERS");

runner.submitResume(
    turnId,
    AgentResumeCommand.userInput(callId, formData)
        .withMetadata("submittedBy", "user-1001"));
```

Runner 校验 callId，把表单数据写成匹配原 `request_user_input` ToolCall 的 ToolMessage，然后回到模型
阶段继续原 Turn。这样模型原生 Tool Calling 消息序列保持完整，也不会重新生成问题或表单参数。

### 底层主动挂起

没有模型 ToolCall 的自定义控制流程仍可显式建立纯文本等待点：

```java
AgentTurn blocked = runner.suspend(
    turn,
    AgentSuspension.userInput("请提供订单号"));
```

这种兼容路径没有 correlationId，恢复时使用 `userInput(content)`，内容会作为新的 UserMessage 加入
Prompt。普通模型追问优先使用 `request_user_input`；工具审批、子 Turn 和重试由 Runner 内部创建
Suspension，业务代码不应手工构造这些暂停类型。

## 同步恢复

```java
AgentTurn resumed = runner.resume(
    turnId,
    AgentResumeCommand.userInput("O-1001")
        .withMetadata("source", "web"));
```

审批示例：

```java
AgentTurn resumed = runner.resume(turnId,
    AgentResumeCommand.approveTool(callId)
        .withMetadata("approverId", "u-100"));
```

Runner 会校验当前 Suspension 类型和 correlationId。批准后执行已保存的原 ToolCall；拒绝后写入与原调用关联的工具结果，供模型解释，不会执行函数体。

## 异步恢复

审批回调与 Worker 不在同一服务时，业务系统应先把审批结果可靠保存到自己的数据库、消息队列或 Inbox，再调用：

```java
runner.submitResume(
    turnId,
    AgentResumeCommand.approveTool(callId));
```

`submitResume` 校验恢复动作并把 Turn 保存为可领取的 `RUNNING` 状态，不会在当前线程继续调用模型或工具；之后由 `AgentWorker` 通过 Turn Lease 领取。Framework 不保存外部审批事件，也不提供恢复命令队列。业务系统负责幂等键、消息重试、消费确认和审计记录。

## 幂等与竞态

- 外部事件使用稳定业务幂等键，重复回调只调用一次 `submitResume`。
- 工具审批 correlationId 必须匹配当前待处理 ToolCall。
- Store 版本冲突时重新加载状态，不能覆盖较新快照。
- Turn 已终止后到达的恢复动作应成为明确失败或业务侧幂等完成，不能重新打开终态。
- 多个审批人竞争时，应由业务审批系统先决定唯一结果。

## 用户体验

配置 `chatMemoryProvider` 后，工具审批暂停会在业务 ChatMemory 中生成
`AgentActionMessage`。它包含稳定 messageId、turnId、actionId、状态和页面可用操作，并且
`modelVisible=false`，因此不会发送给模型或占用模型消息窗口。

页面使用 `ChatMemory.getMessages(count)` 读取时间线窗口：`PENDING` 时显示批准/拒绝按钮，终态时
`getActions()` 为空。用户提交决定后，Runner 使用 expectedVersion CAS 更新原消息为 `APPROVED` 或
`REJECTED`，不删除消息，也不额外追加一条结果消息。页面模型保持简单，并发审批也不会用旧版本覆盖
终态。

`AgentActionMessage` 是页面当前状态，不是执行授权依据，也不是审计日志。恢复 API 必须继续鉴权并调用
`resume` 或 `submitResume`，由当前 `AgentSuspension` 校验 actionId。审批人、渠道、理由应写入
`AgentResumeCommand` metadata；完整审计由业务系统通过 `AgentEventListener` 保存。

未配置 Provider 时，暂停接口仍可直接向前端返回 Turn ID、状态、Suspension message、correlationId
和必要的安全元数据。不要直接展示模型原始内部内容或敏感工具参数。

`request_user_input` 产生的表单会投影为 `AgentFormMessage`。消息包含 formKey、完整 JSON Schema 和
actionId，并且 `modelVisible=false`。`PENDING` 状态的 `getActions()` 返回 `SUBMIT`；成功提交
后，Runner 使用 expectedVersion CAS 将原消息更新为 `SUBMITTED`，页面不需要关联额外结果消息。

前端直接使用消息携带的 Schema 渲染，不需要额外查询表单注册中心。后端不能信任前端回传的隐藏字段，
必须使用 Suspension 中固化的同一份 Schema 重新校验。

完整的前端 DTO、表单注册和提交示例见 [表单输入](./form-input)。

## 不应做的事

不要用 `Thread.sleep` 等待审批或重试，不要在恢复时创建一个全新 Turn，也不要让模型重新生成待审批参数。暂停点和原 ToolCall 已在 Snapshot 中，正确操作是恢复原 Turn。
