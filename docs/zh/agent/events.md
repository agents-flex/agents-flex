---
title: 事件机制
description: 使用统一的不可变 AgentEvent 接入实时 UI、日志、指标和业务审计。
---

# 事件机制

## 概述

Agent 模块只提供一套观察机制：`AgentRunner` 产生不可变 `AgentEvent`，并同步通知注册的 `AgentEventListener`。Framework 不保存事件；是否写入数据库、Kafka、日志或监控系统由业务决定。

会影响执行结果的扩展仍使用 `AgentMiddleware`。Listener 只能观察已经发生的事件，不能修改模型、工具或 Turn 的控制决策。

## 注册监听器

```java
runner.addEventListener(event -> {
    if (event.getType() == AgentEventType.MODEL_TEXT_DELTA) {
        websocket.send(event.getTurnId(), event.getData().get("content"));
    }
});
```

可以通过 `removeEventListener(listener)` 删除已经注册的监听器。监听器列表线程安全，但监听器实现自身仍需支持多个 Turn 并发调用。

## 事件结构

`AgentEvent` 包含：

- `eventId`：当前发布产生的唯一 ID。
- `turnId`、`rootTurnId`、`parentTurnId`：运行与父子任务关联。
- `agentId`、`agentVersion`：产生事件的 Agent 定义。
- `sequence`：当前 Runner 进程内、同一 turnId 的递增序号。
- `type`、`occurredAt` 和只读 `data`。

Event 本身及 data 中的 Map、List、Iterable 和数组都会被复制并冻结。不受支持的可变对象会转换为字符串，避免监听器持有 Runner 内部状态。

sequence 在 Runner 重建或进程重启后会重新开始，不能直接作为数据库审计游标。

## 事件类型

事件覆盖以下执行节点：

- Turn：开始、完成、失败、取消、暂停、恢复和 Snapshot 保存。
- Step：开始、结束、达到 `maxSteps`。
- Model：开始、结束、文本/推理/ToolCall 增量和迭代上限。
- Tool：开始、进度、完成、失败和审批。
- Planning：计划创建、调整、任务和子 Turn。
- Retry：可恢复异常的延迟重试调度。
- Budget：时间、Token 或工具调用次数达到限制。

Turn 是 Step 的生命周期容器。第一个步骤开始前发布一次 `TURN_STARTED`；每个步骤按
`STEP_STARTED ... STEP_COMPLETED` 成对发布；正常完成、失败、取消或达到限制时，最终的 Turn 终止
事件在最后一个 `STEP_COMPLETED` 之后发布。监听器收到 Turn 终止事件后，不会再收到该 Turn 的 Step
事件。同一对 Step 事件使用相同的 1-based `stepCount`；Step 内产生暂停时，`TURN_SUSPENDED` 同样在
对应的 `STEP_COMPLETED` 之后发布。

`SNAPSHOT_SAVED` 表示状态已经保存，不表示整个 Turn 已完成。

## 工具上报进度

Runner 会把受控的 `AgentToolContext` 放入工具执行上下文。工具可以发送 `TOOL_PROGRESS`：

```java
AgentToolContext context = AgentToolContext.current();
context.emitProgress("uploading", Collections.singletonMap("percent", 50));
```

进度事件只用于观察，不改变工具的最终结果。长时间运行的工具还可以调用
`context.isCancellationRequested()` 动态检查 Turn 是否已被请求取消。

## 业务持久化

Framework 不提供 EventStore。业务系统可以直接在监听器中转发事件：

```java
runner.addEventListener(event -> eventRepository.save(event));
runner.addEventListener(event -> kafkaPublisher.publish(event));
```

监听器异常会被 Runner 记录并隔离，不会让 Agent 执行失败。因此，上述直接写入适合尽力而为的日志和实时通知。

需要可靠审计、计费或跨进程消费时，应由业务系统使用有界队列、事务 Outbox 或消息平台，并自行定义持久化 ID、顺序、重试和消费游标。不要把 Framework 的进程内 sequence 当作可靠游标。

ChatMemory 中的 `AgentActionMessage` 只表示页面当前审批状态；CAS 更新原消息不会保留每次竞争写入的
历史。因此合规审计不能依赖 ChatMemory，仍应由 Listener 或审批业务自己的 Outbox 保存完整事件。

## 与 Middleware 的区别

| 扩展 | 作用 | 能否影响执行 |
| --- | --- | --- |
| `AgentEventListener` | UI、日志、指标、业务事件转发 | 否 |
| `AgentMiddleware` | 包装或短路 step、model、tool | 是 |

鉴权、参数校验、缓存和调用替换放在 Middleware；统计、通知和业务侧事件持久化放在 Listener。
