---
title: Agent
description: 深入理解不可变 Agent 定义、模型、工具、策略、版本和扩展属性。
---

# Agent

## 概述

`Agent` 是一个不可变的运行定义，描述“由哪个模型、遵循什么指令、可以使用哪些工具、受哪些策略控制”。它不包含某次请求的消息、状态或结果，因此可被多个 `AgentTurn` 复用。

这种分离类似于配置元数据与运行实例的关系：修改 Agent 定义不会直接修改历史 Turn；恢复历史 Turn 时，`AgentLoader` 必须按快照中的 ID 和版本重新加载兼容定义。

## 创建 Agent

```java
Agent agent = Agent.builder("order-assistant")
    .id("order-assistant")
    .version("2026-08-01")
    .description("查询订单并处理售后请求")
    .instructions("先查询订单事实；任何退款操作都必须使用工具。")
    .chatModel(chatModel)
    .chatOptions(chatOptions)
    .tools(Arrays.asList(queryOrder, refundOrder))
    .build();
```

构建时会校验模型非空、名称非空、版本非空以及工具名唯一。未设置 `id` 时使用 `name`，未设置版本时为 `"1"`。

## 属性说明

| 属性 | 作用 | 是否进入 Snapshot |
| --- | --- | --- |
| `id`、`version` | 恢复时定位定义 | 保存标识，不保存整个 Agent |
| `name`、`description` | 展示及规划时描述能力 | 否 |
| `instructions` | 注入 `SystemMessage` | 消息中保存当前结果 |
| `chatModel`、`chatOptions` | 执行模型请求 | 否，恢复时重装配 |
| `multimodalChatModel` | 当前 Prompt 含图片、音频、视频或文件时使用的模型 | 否，恢复时重装配 |
| `tools`、`toolInterceptors` | 工具协议及执行链 | 否，恢复时重装配 |
| `executionPolicy` | 迭代、重试、预算 | 有效策略会保存 |
| `planningPolicy` | 自动规划及委派约束 | 计划状态保存，定义重装配 |
| `maxAttachedTurns` | 单次模型调用最多附加的完整 Turn 数 | 否，恢复时随 Agent 定义重装配 |
| `maxAttachedMessages` | 上下文窗口的消息数量安全上限 | 否，恢复时随 Agent 定义重装配 |
| `compressionPolicy` | 统一配置工具 Turn 归一化、语义压缩、增量触发和状态持久化 | 否，恢复时随 Agent 定义重装配 |
| `middlewares` | 包装 step、模型和工具 | 否 |
| `attributes` | 平台扩展元数据 | 否 |

## 指令与工具

系统指令用于定义身份、边界和决策原则；工具描述用于告诉模型具体能力。不要只在指令中声称某项能力而不提供工具，也不要把动态业务状态写死在指令里。

```java
Agent agent = Agent.builder("order-assistant")
    .instructions("查询类请求可直接执行；退款类请求调用 refund_order。")
    .chatModel(chatModel)
    .tool(queryOrder)
    .tool(refundOrder)
    .toolApprovalPolicy((turn, call, tool) ->
        "refund_order".equals(tool.getName())
            ? ToolApprovalDecision.requireApproval()
                .code("REFUND_APPROVAL")
                .message("退款需要人工审批")
                .build()
            : ToolApprovalDecision.ALLOW)
    .build();
```

规划开启后，框架会注入保留工具名 `create_task_plan`；允许重规划时还会注入
`update_task_plan`。业务工具不能使用这些名称。

需要让模型在长任务中请求结构化用户输入时，可以通过 `AgentUserInputTool.builder().form(...)` 注册
允许选择的表单。模型调用
稳定工具名 `request_user_input` 后，Runner 会暂停原 Turn；该控制工具不经过业务 Tool 函数、审批
策略或 ToolInterceptor。模型只选择 formKey，完整 JSON Schema 由 Runner 固化进 Snapshot 并提供给前端。

## 策略组合

`Agent` 聚合执行所需的策略和参数：

- `AgentExecutionPolicy`：最大模型迭代、Runner 总 step、工具错误策略、重试和预算。
- `ToolApprovalPolicy`：允许、拒绝或要求审批。
- `AgentPlanningPolicy`：是否规划、允许委派给谁、最大任务数和重规划次数。
- `maxAttachedTurns`：每次模型调用最多保留多少个完整 Turn，默认 10。
- `maxAttachedMessages`：上下文消息数量安全上限，默认 100；不会从 ToolCall/ToolMessage 中间截断。
- `compressionPolicy`：统一控制较早历史的工具 Turn 归一化、语义压缩和增量摘要；只改变模型上下文，不清理 ChatMemory。

单次 Turn 可通过 `AgentTurnOptions` 覆盖执行策略，但不会覆盖 Agent 的工具或审批策略。工具返回规模由 Tool 契约控制，Runner 不会根据内容大小改写结果。

## 版本管理

只要变化会影响历史 Turn 的恢复行为，就应发布新版本，例如：

- 删除或重命名工具。
- 改变工具参数 Schema 或副作用语义。
- 修改会影响待审批调用的 Middleware。

`AgentLoader.load(agentId, version)` 必须能够加载仍可能恢复的历史版本。不要让该方法悄悄返回最新版本；显式版本找不到时应返回 `null` 或抛出清晰的业务异常。

## 扩展属性

```java
Agent agent = Agent.builder("order-assistant")
    .chatModel(chatModel)
    .attribute("owner", "order-platform")
    .attribute("riskLevel", "high")
    .build();
```

`attributes` 适合配置平台展示、路由和审计，不会自动发送给模型，也不会进入 Turn 快照。需要影响执行时，应由 Middleware 或业务装配逻辑显式读取。

## 线程安全

Agent 自身的集合在构建后只读，但这不自动保证其中的 `ChatModel`、`Tool`、Interceptor 和 Middleware 线程安全。作为单例复用时，这些组件不应把某个 Turn 的可变数据保存在实例字段中；需要恢复的运行级数据应放在 `AgentTurn` 的受控状态或 metadata 中，其他数据放在外部存储。Middleware 和 Tool 使用的进程内服务应由组件自身以线程安全方式管理。

## 自定义装配

生产平台通常从数据库、配置中心和依赖注入容器组合 Agent：配置表保存 ID、版本和策略，运行时注册表提供模型与工具实现。最终只需由 [AgentLoader](./agent-loader) 返回完整、可执行的 `Agent`。
