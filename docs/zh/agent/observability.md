---
title: 可观测性
description: 为 Agent 建立日志、指标、事件时间线、Trace 关联和运行查询。
---

# 可观测性

## 概述

Agent 的一次用户请求可能跨模型、工具、审批、重试、Worker 和多个子 Run。可观测性的目标不只是打印模型内容，而是能够回答：任务现在在哪里、为什么等待、消耗了多少、谁批准了什么、失败后是否会重试，以及整棵任务树如何演进。

## 关联标识

建议所有日志和 Trace 至少携带：

- `runId`：单个执行实例。
- `rootRunId`：整棵父子任务树。
- `parentRunId`：直接父任务。
- `agentId` 与 `agentVersion`：运行定义。
- `requestId`、`tenantId`、`userId`：来自 Invocation Context。
- `toolCallId` 与 `commandId`：工具和恢复命令幂等关联。

不要把 Prompt、工具参数和模型输出默认完整写入日志；先做分级、脱敏与大小限制。

## 实时进度

Runtime Event 适合 WebSocket/SSE：

```java
runner.addRuntimeEventListener(event -> uiPublisher.publish(
    event.getRunId(), event.getSequence(), event.getType(), event.getData()));
```

UI 应容忍丢事件，并在重连时从 `AgentRunStore` 重新读取当前状态。实时事件不是跨进程恢复日志。

## 审计时间线

持久化 `AgentRunEventStore` 后，按 sequence 增量读取。审批事件应通过 Enricher 或 Resume Command metadata 记录审批人、渠道、策略代码和理由；敏感数据应保存引用而不是原文。

Snapshot 表示当前真相，Event 表示变化历史。排障时先读 Snapshot，再用事件解释到达该状态的路径。

## 指标设计

推荐的低基数指标包括：

- Run 创建、完成、失败、取消、预算终止数量。
- Run 端到端耗时与等待时间。
- 每 Agent 的模型迭代、Token 与工具调用分布。
- 工具成功率、错误率和耗时。
- 重试调度数量及重试后成功率。
- Worker 领取数、Lease 丢失数、可运行积压。
- Command Inbox 的 pending 数量、处理延迟和失败数。
- Snapshot、事件和 Artifact 的写入失败及大小。

`runId`、`toolCallId` 等高基数值只进入日志或 Trace，不作为指标标签。

## Trace 边界

可在 Middleware 中创建 step、model、tool span，在 Listener 中记录最终状态。父子 Run 通过 `rootRunId` 或显式 Trace context 关联。由于 Invocation Context 不持久化，Worker 恢复时应从 Snapshot metadata 中的安全追踪标识重建新的 span link，而不是序列化整个 SDK Context。

## 日志建议

使用结构化日志记录状态转换和关键计数：

```text
event=agent_run_suspended runId=... status=WAITING_FOR_APPROVAL
agentId=order-agent tool=refund_order toolCallId=...
```

错误日志应保留异常类型、phase、retryCount 和是否还会重试。模型返回内容只在受控调试环境记录，并设置采样与脱敏。

## 与 observability 模块集成

Agents-Flex 的通用可观测模块可以覆盖模型和工具底层调用；Agent 自身的状态事件则补充任务级语义。两者通过 request/run 标识关联，不应重复把整段 Prompt 存两份。具体 exporter 和 JDBC 存储参见站点的“可观测”模块文档。

## 健康检查

生产环境应检查 Loader 能否加载 active 与历史版本、各 Store 的读写、Worker 最近心跳、命令积压和最老 runnable Run 年龄。仅检查 HTTP 进程存活，无法发现 Agent 调度已经停止。
