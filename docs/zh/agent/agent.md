---
title: Agent 定义与加载
description: 设计不可变 Agent，配置模型、工具、策略、版本，并通过 AgentLoader 从业务系统组装定义。
---

# Agent 定义与加载

## 概述

`Agent` 是一份不可变的可执行定义。它回答“这个智能体能做什么、使用哪个模型、遵守哪些规则”，不保存某个用户正在执行到哪一步。

例如订单助手可以绑定订单查询和退款工具，并规定退款必须审批；每个用户请求则创建独立 `AgentRun`。定义和运行分离后，同一个 Agent 能被多个线程复用，运行记录也不会互相污染。

## 快速开发

```java
Agent agent = Agent.builder("order-assistant")
    .id("order-assistant")
    .version("2026-08-01")
    .description("查询订单状态并协助处理售后问题")
    .instructions("根据用户问题选择工具；不得虚构订单状态。")
    .chatModel(chatModel)
    .chatOptions(chatOptions)
    .tool(queryOrderTool)
    .tool(refundTool)
    .executionPolicy(AgentExecutionPolicy.builder()
        .maxIterations(12)
        .build())
    .build();
```

构建完成后，工具、中间件和 attributes 都是只读集合。模型、Tool 实现本身是否线程安全，仍由对应实现保证。

## 定义包含哪些内容

| 配置 | 用途 |
| --- | --- |
| `id` | Checkpoint 恢复和子 Agent 加载使用的稳定标识 |
| `version` | 运行创建时冻结的定义版本 |
| `name` | 日志和界面使用的名称 |
| `description` | 平台和规划模型可见的能力描述 |
| `instructions` | 注入模型的系统指令 |
| `chatModel/chatOptions` | 模型实现和生成参数 |
| `tools` | 当前版本实际可执行的工具 |
| `executionPolicy` | 迭代、重试和预算边界 |
| `planningPolicy` | 模型是否可以创建任务计划和委派子 Agent |
| `toolApprovalPolicy` | ToolCall 执行前的结构化决策 |
| `middlewares` | 步骤、模型和工具调用扩展链 |
| `contextManager` | 调用模型前压缩持久化消息 |
| `attributes` | 平台自定义的定义级配置数据 |

`attributes` 不会自动发送给模型，也不会替代业务配置表。平台可以用它保存模式参数、发布标签或适用场景，再由 Middleware 或自定义执行模式读取。

## Tool 属于 Agent

Runner 恢复工具调用时，不再访问全局 Tool Registry，而是先加载 Snapshot 指定版本的 Agent，再通过工具名从该 Agent 取得 Tool：

```java
Tool tool = agent.getTool(toolCall.getName());
```

因此同一个 Agent 内工具名必须唯一。Tool metadata 随 Tool 定义存在，可以描述风险等级、绑定来源和领域，但不进入 `AgentRunSnapshot`；恢复后以加载到的 Agent 版本为准。

## 执行策略

```java
AgentExecutionPolicy policy = AgentExecutionPolicy.builder()
    .maxIterations(10)
    .maxSteps(200)
    .retryPolicy(AgentRetryPolicy.builder()
        .maxRetries(3)
        .initialDelayMillis(1_000)
        .maxDelayMillis(30_000)
        .multiplier(2.0)
        .build())
    .budget(AgentBudget.builder()
        .maxDurationMillis(120_000)
        .maxTotalTokens(30_000)
        .maxToolCalls(20)
        .build())
    .build();
```

策略是 Agent 的默认值。某类任务需要更小预算时，可通过 `AgentRunOptions.executionPolicy(...)` 覆盖；实际策略会写入 Snapshot，恢复时不会跟随 Agent 当前默认值漂移。

## 从业务系统加载 Agent

生产平台通常把配置拆在多张表中：基础信息、模型连接、工具授权、审批策略和版本发布记录。`AgentLoader` 只要求最终组装出完整 Agent：

```java
public final class DatabaseAgentLoader implements AgentLoader {
    @Override
    public Agent load(String agentId, String version) {
        AgentConfig config = repository.find(agentId, version);
        return config == null ? null : assemble(config);
    }

    @Override
    public Agent loadActive(String agentId) {
        AgentConfig config = repository.findPublished(agentId);
        return config == null ? null : assemble(config);
    }
}
```

`loadActive` 用于创建新任务和模型选择子 Agent；`load(id, version)` 用于恢复已有 Run。加载器不需要保存 Agent 对象，也不需要 register API。

## 版本为什么重要

假设任务在审批前生成了“退款 100 元”的 ToolCall。审批期间平台把退款工具参数改成另一种含义，如果恢复时直接加载最新定义，旧调用可能被错误解释。Snapshot 保存 `agentId + agentVersion`，Runner 恢复时精确加载创建该 Run 的版本。

版本不要求平台采用某种编号格式，但同一 ID 和版本必须具有稳定执行语义。已经没有运行引用某版本后，平台才适合归档其运行时配置。

## 本地加载器

测试和 Demo 可以使用：

```java
AgentLoader loader = new InMemoryAgentLoader(agent, researchAgent, codeAgent);
AgentRunner runner = AgentRunner.builder()
    .agentLoader(loader)
    .build();
```

同一 ID 传入多个版本时，最后一个作为 active 版本，全部版本仍可通过 `load(id, version)` 精确读取。

## 设计边界

不要把用户消息、当前审批人、Token 用量或 Worker Lease 放进 Agent；这些属于 Run。也不要把数据源连接、HTTP Request 等进程内对象放进 attributes；请求级服务应通过 `AgentInvocationContext` 传递。

