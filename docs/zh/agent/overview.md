---
title: Agent 概述
description: 了解 Agents-Flex Agent 运行时解决的问题、核心概念、适用场景与能力边界。
---

# Agent 概述

## 概述

`agents-flex-agent` 是建立在 Agents-Flex `ChatModel` 与 Tool Calling 之上的有状态 Agent 运行时。它不仅让模型调用 Java 工具，还把一次任务建模为可以检查、暂停、持久化、恢复和分布式调度的 `AgentRun`。

普通聊天调用通常是“发送 Prompt，得到一次响应”。真实业务任务则可能经历多轮模型判断、多个有副作用的工具、人工审批、失败退避、子任务以及进程重启。Agent 模块解决的正是这些控制面问题：模型决定做什么，运行时保证执行过程可控、可恢复、可审计。

## 适用场景

- 客服助手查询订单、创建工单，并在退款等写操作前等待审批。
- 运维助手拆解排障目标，委派给分析、检索和验证 Agent。
- 长耗时任务由后台 Worker 领取，服务重启后继续执行。
- 对模型调用次数、Token、工具次数和总时长设置硬预算。
- 通过 Middleware 和 AgentEventListener 接入鉴权、审计、指标与流式 UI。

如果需求只是一次无工具的文本生成，直接使用 `ChatModel` 更简单；如果流程完全确定且每一步都由业务代码决定，工作流引擎往往更合适。Agent 的价值在于：执行路径需要模型动态决策，同时业务又要求可靠的运行控制。

## 核心对象

| 对象 | 职责 | 生命周期 |
| --- | --- | --- |
| `Agent` | 定义模型、指令、工具和策略 | 可复用、不可变 |
| `AgentRun` | 保存一次任务的消息、状态、计数和暂停点 | 每次任务一个 |
| `AgentRunner` | 推进状态机，执行模型与工具，保存 Snapshot | 应用级复用 |
| `AgentRunSnapshot` | `AgentRun` 的可持久化副本 | 每个 Snapshot 一版 |
| `AgentLoader` | 按 Agent ID 与版本重新装配运行定义 | 应用级服务 |
| `AgentWorker` | 使用 Lease 领取并推进后台任务 | Worker 进程级 |

三者最重要的边界是：`Agent` 不保存对话状态，`AgentRun` 不负责主动执行，`AgentRunner` 不长期持有任务状态。

## 一次运行如何推进

Runner 内置状态机采用模型原生 ToolCall 协议：

1. 创建 `READY` Run，并保存初始 Snapshot。
2. 调用模型；没有 ToolCall 时以最终消息完成。
3. 有 ToolCall 时先保存调用参数，再进行审批和工具执行。
4. 把 `ToolMessage` 写回 Prompt，再次调用模型。
5. 遇到审批、用户输入、子 Run 或重试时间时进入阻塞状态。
6. 外部恢复事件到达后从原阶段恢复，而不是重新生成原 ToolCall。

这种设计使人工审批不会丢失模型已经生成的参数，也让重启后的执行可以从稳定边界继续。

## 能力地图

| 需求 | 对应能力 |
| --- | --- |
| 多轮工具执行 | `AgentRunner` |
| 人工介入 | `AgentSuspension`、`AgentResumeCommand` |
| 长任务恢复 | `AgentRunSnapshot`、`AgentRunStore` |
| 分布式执行 | `AgentWorker`、Lease、乐观版本 |
| 动态拆解任务 | `AgentPlanningPolicy`、父子 Run |
| 上下文过长 | `maxAttachedMessages`、`AgentContextManager`、Tool 分页或摘要 |
| 资源限制 | `AgentExecutionPolicy`、`AgentBudget` |
| 扩展执行链 | `AgentMiddleware` |
| 监控与审计 | `AgentEvent`、`AgentEventListener` |

## 设计收益

- **定义与状态分离**：同一个 Agent 定义可安全服务多个 Run。
- **阻塞而非占线程**：审批与退避通过状态和时间戳表达，不使用线程 `sleep` 等待。
- **恢复语义稳定**：快照绑定 Agent ID 和 Agent 版本。
- **并发写入受控**：Store 使用版本号，Worker 使用带 `leaseId` 的 fencing token。
- **扩展点明确**：模型、工具、加载、存储、上下文、事件和 Middleware 均有独立接口。

## 默认实现的边界

无参 `AgentRunner` 使用进程内 Loader 和 Store，适合测试与单实例试用。内存实现不跨 JVM、不抗重启，也不能构成生产级多实例协调。生产部署应持久化 `AgentRunStore`，并提供能够加载历史 Agent 版本的 `AgentLoader`。

下一步从[快速开始](./getting-started)创建第一个可调用工具的 Agent。
