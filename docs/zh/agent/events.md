---
title: 事件机制
description: 区分实时事件、持久化运行事件和生命周期监听器，并实现流式 UI 与可靠审计。
---

# 事件机制

## 概述

Agent 模块提供三种不同保证的观察机制：`AgentListener` 用于粗粒度生命周期回调，`AgentRuntimeEvent` 用于当前 JVM 的低延迟细粒度事件，`AgentRunEvent` 用于可持久化、可断点续读的审计事件。三者不是重复 API，而是分别服务于业务回调、实时体验和可靠消费。

## 选择事件通道

| 机制 | 典型用途 | 是否持久化 | 顺序范围 |
| --- | --- | --- | --- |
| `AgentListener` | 指标、日志、轻量通知 | 否 | 调用线程顺序 |
| Runtime Event | 流式文本、工具进度、实时 UI | 否 | 当前 JVM 内按 runId 递增 |
| Run Event | 审计、断点消费、状态时间线 | 是 | Store 内按 runId 严格递增 |

## 实时事件

```java
runner.addRuntimeEventListener(event -> {
    if (event.getType() == AgentRuntimeEventType.MODEL_TEXT_DELTA) {
        websocket.send(event.getRunId(), event.getData().get("text"));
    }
});
```

Runtime Event 包含 `runId`、`rootRunId`、`parentRunId`、Agent ID/版本、sequence、type 和 data。事件类型覆盖 step、模型增量、工具进度、上下文压缩、命令、子任务和终态。

监听器在发布线程同步执行；单个监听器异常会隔离，但耗时操作仍会拖慢 Run。网络发送应进入有界队列，并定义队列满时的降级策略。

### 工具上报进度

Runner 会把 `AgentToolProgressEmitter` 放入工具执行上下文 attributes。自定义 Tool 或拦截器可取出并发送 `TOOL_PROGRESS`，进度数据只用于实时观察，不改变工具最终返回值。

## 持久化事件

```java
List<AgentRunEvent> page = runner.getEventStore()
    .load(runId, lastSequence, 100);

for (AgentRunEvent event : page) {
    handle(event);
    lastSequence = event.getSequence();
}
```

`afterSequence` 是排他游标，返回结果按 sequence 升序。自定义 `AgentRunEventStore` 必须：

- 为同一 `runId` 原子分配严格递增 sequence。
- 按 `eventId` 幂等追加，重复写入返回原事件。
- 保证 `load` 的稳定顺序与 limit 语义。
- 复制或序列化事件，避免调用方修改已存状态。

持久化类型包括 Run、模型、工具、审批、重试、预算、计划、任务、子 Run 与 Snapshot 等关键节点。它不是完整 Token 流；流式 delta 只存在于 Runtime Event。

## 增强审计字段

```java
runner.addEventEnricher((run, type) -> Map.of(
    "tenantId", String.valueOf(run.getInvocationContext().getTenantId()),
    "environment", "prod"));
```

Enricher 的返回值会合并到持久化事件 attributes。attributes 仅支持字符串信息，适合租户、部署区域、业务类型和追踪 ID。敏感信息与大对象不应写入事件。

## 消费者设计

可靠消费者应按 `runId + sequence` 保存游标，并把处理实现为幂等。Store 保证事件追加幂等，不等于外部消费者 exactly-once。对于整棵父子任务树，可通过 `rootRunId` 关联 Runtime Event；持久化事件当前以各 Run 的时间线分别读取。

## 自定义 EventStore

关系数据库可以使用 `(run_id, sequence)` 唯一索引维持顺序，并为 `event_id` 建唯一索引。sequence 分配与插入应在同一事务中。高并发实现可按 runId 行锁、数据库序列分区或原子计数器选择方案，但不能先读最大值再无锁写入。

## 常见误区

- Runtime sequence 在 JVM 重启后不连续，不能作为审计游标。
- Listener 和实时事件不是可靠消息投递。
- `SNAPSHOT_SAVED` 表示状态已保存，不表示整个 Run 已完成。
- 事件用于描述状态变化，当前状态仍应从 `AgentRunStore` 读取。
