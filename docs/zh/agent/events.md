---
title: 实时事件、持久化事件与审计
description: 区分细粒度进程内事件和可断点读取的生命周期事件，并用于流式 UI、追踪、审计和报表。
---

# 实时事件、持久化事件与审计

## 概述

Agent 同时需要低延迟 UI 和可靠历史。把每个 Token 都写数据库成本很高，只在内存发布生命周期事件又无法审计，因此框架提供两条事件通道：

- `AgentRuntimeEventStream`：当前 JVM 内的细粒度实时事件；
- `AgentRunEventStore`：跨进程保存的生命周期事件。

## 快速开发

监听实时事件：

```java
runner.addRuntimeEventListener(event -> {
    if (event.getType() == AgentRuntimeEventType.MODEL_TEXT_DELTA) {
        websocket.send(event.getRunId(), event.getData().get("content"));
    }
});
```

读取持久化事件：

```java
List<AgentRunEvent> page = eventStore.load(runId, afterSequence, 100);
for (AgentRunEvent event : page) {
    afterSequence = event.getSequence();
}
```

## 实时事件

实时类型覆盖模型开始/完成、文本与 reasoning 增量、ToolCall 增量、工具进度、审批、Checkpoint、命令、上下文压缩、Artifact 外置、规划任务和终止状态。

每个 Run 在当前 JVM 内拥有递增 sequence。监听器在发布线程同步执行，必须快速返回；单个监听器异常会被隔离，但慢监听器仍会拖慢运行。生产 UI 通常把事件快速投递到 WebSocket、SSE 或内部消息队列。

实时事件不承担跨进程可靠投递，进程重启后 sequence 也不会延续。

## 持久化事件

`AgentRunEvent` 记录 eventId、runId、Run 内严格递增 sequence、类型、时间和字符串 attributes。EventStore 必须幂等追加 eventId，并原子分配 sequence。

它适合构建：

- Run 时间线和问题追溯；
- 模型、工具、审批和重试耗时统计；
- 调用状态、成功率和预算终止报表；
- 消费者基于 afterSequence 断点读取；
- 数据库 Outbox 或消息总线同步。

持久化事件是状态变化轨迹，Snapshot 是当前事实。恢复运行只依赖 Snapshot，不通过重放事件重建状态。

## 事件增强

```java
runner.addEventEnricher((run, type, attributes) -> {
    Map<String, String> enriched = new LinkedHashMap<>(attributes);
    enriched.put("tenantId", String.valueOf(run.getMetadata().get("tenantId")));
    enriched.put("module", "order-center");
    return enriched;
});
```

平台可以补充账号、接口、模块、任务类型和配置版本，再在查询层组合用户账号、调用内容、时间、状态和结果。敏感 Prompt、密钥和完整工具结果不应直接写入事件。

## AgentListener

`AgentListener` 是更简单的对象回调接口，适合进程内日志和指标。它能直接读取 AgentRun，但不提供持久化序号。新建可靠审计功能时优先使用 EventStore；需要细粒度流式内容时使用 RuntimeEventStream。

## 指标示例

一次 Run 的完成率来自终止事件；延迟可由 RUN_STARTED 与终止事件计算；模型延迟由 MODEL_STARTED/MODEL_COMPLETED 计算；工具成功率由 TOOL_STARTED、TOOL_COMPLETED 和 TOOL_FAILED 聚合。平台可以按 Agent ID、版本、任务类型和租户维度生成报表，而无需让运行时内置固定统计表。
