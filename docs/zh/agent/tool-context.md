---
title: AgentToolContext
description: 在工具执行期间获取当前 AgentTurn 身份、幂等键、表单数据和受控进度能力。
---

# AgentToolContext

## 概述

`AgentToolContext` 是 Runner 在执行 Tool 时提供的受控上下文。它让工具获取当前 Turn 和 ToolCall 的稳定身份，而不需要依赖全局变量或直接操作 Runner 状态机。

```java
AgentToolContext context = AgentToolContext.current();
String turnId = context.getTurnId();
String toolCallId = context.getToolCallId();
String idempotencyKey = context.getIdempotencyKey();
```

上下文只在当前 Tool 调用线程和执行范围内有效。不要缓存到异步任务、静态字段或跨请求对象中。

## 可用信息

| API | 用途 |
| --- | --- |
| `current()` | 获取当前工具执行上下文 |
| `getTurnId()` | 关联当前 Turn |
| `getAgentId()` / `getAgentVersion()` | 关联执行时使用的 Agent 定义版本 |
| `getTool()` / `getToolCall()` | 读取当前工具和模型生成的调用参数 |
| `getTurnId()` / `getToolCallId()` | 关联日志、审计和外部系统 |
| `getIdempotencyKey()` | 为外部副作用建立幂等记录 |
| `getSubmittedFormData()` | 读取表单恢复后提交的结构化数据 |
| `getProgressEmitter()` | 发布工具执行进度事件 |
| `isCancellationRequested()` | 在长任务安全边界检查取消请求 |

## 外部副作用与幂等

```java
public String createTicket(Map<String, Object> args) {
    AgentToolContext context = AgentToolContext.current();
    String key = context.getIdempotencyKey();

    Ticket existing = ticketStore.findByIdempotencyKey(key);
    if (existing != null) {
        return existing.getId();
    }

    Ticket ticket = ticketService.create(args, key);
    ticketStore.saveIdempotencyRecord(key, ticket.getId());
    return ticket.getId();
}
```

重试、审批恢复和 Worker 接管都可能再次进入同一个 ToolCall。外部写入必须使用稳定幂等键，不能只依赖 JVM 内存标记。

## 表单输入

工具可以在产生副作用前抛出 `AgentFormRequiredException`，Runner 会保存表单定义并让 Turn 进入 `WAITING_FOR_USER`。恢复后，工具通过上下文读取提交值：

```java
AgentToolContext context = AgentToolContext.current();
Map<String, Object> values = context.getSubmittedFormData();
String subject = (String) values.get("subject");
```

表单机制的完整流程见[表单输入](./form-input)。

需要区分两个生命周期：`AgentToolContext` 只在一次 Java Tool 的同步调用范围内创建，包含 Tool、ToolCall、
进度发布器和取消检查器，调用结束后即失效；它不会被放入 Snapshot，也不会跨线程自动传播。

真正可持久化的是当前 Turn 的 `AgentTurnState.toolInputData`。Runner 按 `toolCallId` 保存表单数据，并随
`AgentTurnSnapshot` 交给 `AgentTurnStore` 保存。恢复同一个被表单挂起的 Turn 时，Runner 会用这份数据创建
新的 `AgentToolContext`，所以 `getSubmittedFormData()` 可以读到之前的提交内容。

如果原 Turn 已被拒绝、完成或取消，之后创建新的 Turn 不会自动继承这份数据。新 Turn 即使使用同一个
`conversationId`，也只会读取 ChatMemory 中的模型消息，不会把旧 Turn 的 `toolInputData` 注入新的 ToolCall。
需要再次收集数据时，工具应重新抛出 `AgentFormRequiredException`；确实要复用旧数据时，应由业务系统显式
读取并作为新的业务输入或 metadata 传入，而不是依赖 `AgentToolContext`。

表单可能包含敏感信息。Store 序列化、日志和 ChatMemory 投影应按业务要求脱敏、加密或设置保留期限；不要
尝试自行序列化 `AgentToolContext`、Tool 或其中的回调对象。

## 进度与取消

```java
AgentToolContext context = AgentToolContext.current();
context.emitProgress("正在查询远程系统", Collections.singletonMap("percent", 40));

if (context.isCancellationRequested()) {
    return "任务已取消";
}
```

进度只用于观察，不改变 Tool 的返回值。取消检查应放在分页、轮询和批处理等安全边界；工具无法被 Runner 强制中断，必须由工具自身配合检查。

## 与其他扩展点的边界

| 需求 | 推荐位置 |
| --- | --- |
| 读取当前 ToolCall 身份、幂等键和表单值 | `AgentToolContext` |
| 包装所有 Agent Step、模型调用和工具调用 | `AgentMiddleware` |
| 包装通用 Tool 执行前后逻辑 | `ToolInterceptor` |
| 监听模型、工具、审批和 Turn 生命周期 | `AgentEventListener` |
| 暂停、恢复或取消 Turn | 业务层调用 `AgentRunner` |

`AgentToolContext` 不提供修改 Turn 状态的方法。需要暂停表单应抛出 `AgentFormRequiredException`，需要取消应由业务层调用 `runner.cancel(turnId)`。

相关文档：[Agent Middleware](./middleware)、[事件机制](./events)、[人工审批](./human-approval)、[挂起和恢复](./suspend-resume)。
