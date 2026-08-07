---
title: Agent 概述
description: 了解 Agents-Flex Agent 的静态能力、运行控制、持久化、扩展机制和配置方式。
---

# Agent 概述

## Agent 解决什么问题

`agents-flex-agent` 是建立在 `ChatModel` 和 Tool Calling 之上的可恢复 Agent 运行时。
它不只负责“让模型调用 Java Tool”，还把一次任务建模为可以暂停、持久化、恢复、取消、重试和分布式调度的 `AgentTurn`。

普通聊天通常是“发送 Prompt，得到一次响应”；真实业务任务可能经历多轮模型判断、多个工具、人工审批、表单输入、失败退避、子任务和进程重启。Agent 模块负责这些执行控制能力：

- 模型负责决定下一步是回答、调用工具、请求表单还是创建计划。
- `AgentRunner` 负责按照状态机推进，并在安全边界保存 Snapshot。
- 业务系统通过 Store、Loader、Listener 和 ChatMemory 接入持久化、恢复、审计和页面展示。

如果需求只是一次无工具文本生成，直接使用 `ChatModel` 更简单；如果每一步都由业务代码完全确定，工作流引擎通常更合适。Agent 适用于“路径需要模型动态决策，但执行又必须可靠可控”的场景。

## 三层配置模型

Agent 的能力不是集中在一个巨大配置对象中，而是分为三层：

| 层次 | 配置对象 | 解决的问题 | 生命周期 |
| --- | --- | --- | --- |
| 静态定义 | `Agent.Builder` | 这个 Agent 能做什么、使用什么模型和工具 | 可复用、不可变 |
| 运行基础设施 | `AgentRunner.Builder` | Turn 如何保存、加载 Agent 和关联业务会话 | 应用级复用 |
| 单次运行 | `AgentTurnOptions.Builder` | 本次是否流式、使用哪套策略和业务元数据 | 随 Snapshot 保存 |

这种分层允许同一个 Agent 定义被多个会话和多个 Worker 并发使用，同时为某一类任务临时覆盖预算或流式设置。

## 先选择正确的入口

下表按业务场景选择入口，避免把“新问题”“恢复旧任务”和“后台调度”混为一谈：

| 场景 | 推荐入口 | 关键规则 |
| --- | --- | --- |
| 当前请求内完成短任务 | `runner.run(...)` | 返回完成、失败或阻塞状态，不保证一定是 `COMPLETED` |
| 长任务、HTTP 请求不应等待 | `runner.start(...)` + `AgentWorker` | `start` 只创建 `READY` Turn，不会自动开线程 |
| 审批或表单已提交 | `resume(turnId, command)` | 校验 Suspension 后立即继续当前 Turn |
| 回调服务只负责入队 | `submitResume(turnId, command)` | 只恢复为可运行状态，由 Worker 执行 |
| 用户发送了新的问题 | 创建新的 Turn | 同一会话仍有活动 Turn 时由业务排队或返回冲突 |
| 继续一个被取消/失败的 Turn | 不复用原 Turn | 终态 Turn 不可重新打开；新问题应创建新 Turn |

审批和表单是对“已经暂停的原 ToolCall”提供结果，不是新的 `UserMessage`；普通追问也不能替代恢复命令。

## Agent 的能力配置

`Agent` 描述可复用的静态能力。构建完成后，Agent 及其工具集合不可变。

### 身份与模型

| Builder 方法 | 能力 |
| --- | --- |
| `id(...)` | Snapshot 恢复和 `AgentLoader` 使用的稳定 ID |
| `version(...)` | 绑定任务创建时的 Agent 配置版本 |
| `name(...)` / `description(...)` | 展示、路由和任务规划时使用的描述 |
| `instructions(...)` | 注入模型的系统指令 |
| `chatModel(...)` | 必填，提供模型调用能力 |
| `chatOptions(...)` | 模型参数模板，例如 model、temperature、maxTokens 和 thinking |

`ChatOptions` 是模型参数，不包含 Agent 的流式开关。流式由 `AgentTurnOptions.streaming(...)` 决定，并随 Turn 保存。

### 工具与工具扩展

| Builder 方法 | 能力 |
| --- | --- |
| `tool(...)` / `tools(...)` | 注册模型可见且 Runner 可执行的 Tool |
| `toolInterceptor(...)` | 为当前 Agent 的 Tool 调用增加权限、审计、参数校验或异常转换 |
| `middleware(...)` / `middlewares(...)` | 包装 Step、模型调用和 Tool 调用，必要时注册动态 Tool Resolver |

Agent 会按工具名称建立索引。模型返回的 ToolCall 必须能解析到唯一 Tool；动态 Tool 可以由 Middleware 注册的 `AgentToolResolver` 提供。

### 执行与安全策略

| Builder 方法 | 能力 |
| --- | --- |
| `executionPolicy(...)` | 最大迭代、最大 Step、预算、重试和工具错误处理 |
| `toolApprovalPolicy(...)` | 对退款、发布、删除等有副作用 Tool 要求人工批准 |
| `planningPolicy(...)` | 开启模型自主任务规划、父子 Turn 和重规划限制 |
| `maxAttachedTurns(...)` | 按完整 Turn 限制发送给模型的历史窗口，不拆分 Tool 协议 |
| `maxAttachedMessages(...)` | 上下文消息数量安全上限，不删除完整历史 |
| `compactCompletedToolTurns(...)` | 将较早已完成工具 Turn 归一化为用户问题和最终 AI 回复 |
| `compressionKeepRecentTurns(...)` | 保留最近若干完整 Turn，不参与规则或语义压缩 |
| `contextCompressor(...)` | 对更早历史执行可选的业务语义压缩，仅改变模型上下文视图 |

`AgentExecutionPolicy` 的典型配置：

```java
AgentExecutionPolicy policy = AgentExecutionPolicy.builder()
    .maxIterations(8)
    .maxSteps(100)
    .budget(AgentBudget.builder()
        .maxDurationMillis(120_000)
        .maxTotalTokens(20_000)
        .maxToolCalls(20)
        .build())
    .retryPolicy(AgentRetryPolicy.builder()
        .maxRetries(2)
        .build())
    .toolErrorStrategy(ToolErrorStrategy.RETURN_ERROR_TO_MODEL)
    .build();
```

工具错误交回模型时，还可以在该策略中配置 `ToolErrorMessageFactory`，用于脱敏、映射业务错误码或提供补救建议。`ToolErrorStrategy` 决定控制流，Factory 决定模型看到的消息内容。

### 任务规划能力

开启 `AgentPlanningPolicy` 后，Runner 向模型提供内置规划工具。模型可以创建任务、选择目标 Agent、等待子任务并汇总结果。

```java
AgentPlanningPolicy planning = AgentPlanningPolicy.builder()
    .enabled(true)
    .maxTasks(6)
    .maxDepth(2)
    .maxReplans(1)
    .finalSummaryRequired(true)
    .build();
```

规划能力适合跨工具、跨 Agent 的动态任务；简单的固定顺序流程不必开启。

## AgentRunner 的能力配置

`AgentRunner` 是无长期业务状态的执行器。它可以作为应用级单例复用，真正的 Turn 状态由 `AgentTurnStore` 保存。

```java
AgentRunner runner = AgentRunner.builder()
    .turnStore(new JdbcAgentTurnStore(dataSource))
    .agentLoader(agentLoader)
    .chatMemoryProvider(conversationId -> chatMemoryStore.get(conversationId))
    .build();
```

| Builder 方法 | 能力 |
| --- | --- |
| `turnStore(...)` | Snapshot、CAS、取消、租约和父子 Turn 的持久化能力 |
| `agentLoader(...)` | 根据 Agent ID 和版本恢复完整 Agent 定义 |
| `chatMemoryProvider(...)` | 可选，将业务会话历史投影到 ChatMemory；不配置时 Runner 不读写业务会话 |
| `addEventListener(...)` | 监听不可变 `AgentEvent`，由业务侧保存审计、推送 UI 或写监控 |

Runner 不负责保存完整事件流，也不维护 `conversationId` 的业务列表。会话列表、ChatMemory 存储和事件审计由业务系统负责。

## 单次 Turn 的配置

当某次调用需要覆盖 Agent 默认策略时，使用 `AgentTurnOptions`，不会修改 Agent 定义：

```java
AgentTurn turn = runner.run(
    agent,
    "分别查询上海和东京当前时间",
    AgentTurnOptions.builder()
        .streaming(true)
        .executionPolicy(AgentExecutionPolicy.builder()
            .maxIterations(4)
            .maxSteps(30)
            .build())
        .metadata("requestId", requestId)
        .metadata("userId", userId)
        .build()
);
```

`streaming`、策略覆盖和 metadata 会随 Snapshot 保存，因此挂起恢复或 Worker 接管后仍保持一致。

## Runner 可以执行哪些操作

| 操作 | 方法 | 说明 |
| --- | --- | --- |
| 创建并执行 | `run(...)` | 推进到完成、失败或阻塞 |
| 只创建 | `start(...)` | 保存 `READY` Snapshot，交给 Worker 或稍后执行 |
| 单步推进 | `step(...)` | 执行一个状态机 Step，适合自定义调度 |
| 继续执行 | `runUntilBlocked(...)` | 推进到终态或等待审批、表单、子 Turn、重试 |
| 保存快照 | `saveSnapshot(...)` | 在业务需要时显式保存当前状态 |
| 挂起 | `suspend(...)` | 工具或业务流程主动创建等待点 |
| 恢复执行 | `resume(...)` | 提交审批或表单结果并立即继续 |
| 更新可运行状态 | `submitResume(...)` | 提交恢复命令但不在当前线程执行 |
| 取消 | `cancel(...)` | 持久化取消并在安全边界收束消息历史 |
| 恢复查询 | `restore(...)` | 从 Store 重新装配 Turn |
| 子任务 | `startChild(...)` / `resumeParentFromChild(...)` | 管理父子 Turn 关系 |
| 后台调度 | `AgentWorker` | 使用 Lease 领取可运行 Snapshot |

阻塞状态不是异常，而是正常的业务状态。前端可以根据事件或 ChatMemory 展示审批按钮、表单和重试状态；提交结果后调用对应的 `resume` 或 `submitResume`。

## 能力地图

| 业务需求 | 主要配置或组件 |
| --- | --- |
| 多轮模型与工具协作 | `Agent`、`AgentRunner`、原生 ToolCall |
| 流式输出 | `AgentTurnOptions.streaming`、`AgentEventListener` |
| 人工审批 | `ToolApprovalPolicy`、`AgentSuspensionType.TOOL_APPROVAL` |
| 表单输入 | `AgentUserInputTool`、`AgentFormRequiredException`、`AgentSuspensionType.USER_INPUT` |
| 工具失败交回模型 | `ToolErrorStrategy`、`ToolErrorMessageFactory` |
| 自动重试 | `AgentRetryPolicy`、`RETRY_SCHEDULED` |
| 运行预算 | `AgentBudget`、`maxIterations`、`maxSteps` |
| 任务规划 | `AgentPlanningPolicy`、父子 Turn、`AgentTaskPlan` |
| 上下文窗口 | `maxAttachedTurns`、`maxAttachedMessages`、工具 Turn 归一化、业务侧 ChatMemory 和摘要 |
| 动态工具 | `AgentMiddleware`、`AgentToolResolver` |
| 取消与新问题 | `cancel(...)`、历史收束消息、业务侧并发策略 |
| 进程重启恢复 | `AgentTurnSnapshot`、`AgentTurnStore`、`AgentLoader` |
| 多实例 Worker | Lease、CAS、`AgentWorker` |
| 前端实时状态 | `AgentEvent`、`AgentEventListener`、ChatMemory 消息投影 |

## 核心对象边界

| 对象 | 职责 | 不负责什么 |
| --- | --- | --- |
| `Agent` | 静态模型、指令、工具和策略 | 不保存某次对话或执行状态 |
| `AgentTurn` | 一次输入的可变运行状态、消息、阶段和结果 | 不主动推进自己 |
| `AgentTurnSnapshot` | 可序列化的 Turn 状态 | 不执行模型或工具 |
| `AgentRunner` | 推进状态机、执行模型与工具、处理恢复 | 不长期持有业务任务状态 |
| `AgentTurnStore` | CAS 保存 Snapshot、取消和租约 | 不负责事件审计 |
| `AgentLoader` | 按 ID/版本重新加载完整 Agent | 不保存 Turn |
| `AgentEventListener` | 观察运行过程并交给业务系统 | 不改变 Runner 状态 |

## 生产边界

无参 `AgentRunner` 使用进程内 Loader 和 Store，只适合测试和单实例试用。生产环境至少应：

1. 使用持久化 `AgentTurnStore`，支持 CAS 和租约。
2. 使用可按版本加载 Agent 的 `AgentLoader`。
3. 由业务侧保存 `AgentEvent`，并通过 ChatMemory 或自己的消息表渲染会话页面。
4. 对恢复、审批、表单提交和取消接口做幂等校验。
5. 根据任务风险配置 Tool 审批、预算、重试和错误脱敏策略。

一个典型的跨请求流程如下：业务 API 创建 Turn 并返回 `turnId`；Runner 运行到审批或表单后保存
`WAITING_*` Snapshot；前端通过 ChatMemory 或业务 DTO 渲染待处理操作；用户提交后，业务系统先做鉴权、
幂等和审计，再调用 `submitResume`；Worker 领取并继续执行；最终状态通过事件或查询接口更新页面。
浏览器关闭不会影响 Turn，重新登录后只需根据保存的 `turnId` 恢复原任务。

## 下一步

- [快速开始](./getting-started)
- [AgentRunner](./agent-runner)
- [Agent 配置](./agent)
- [挂起与恢复](./suspend-resume)
- [人工审批](./human-approval)
- [表单输入](./form-input)
- [任务规划](./task-planning)
- [Snapshot 持久化](./snapshot)
- [事件机制](./events)
- [可观测性](./observability)
- [Middleware](./middleware)
- [Worker](./worker)
- [常见问题](./faq)
