---
title: AgentExecutionPolicy
description: 配置 AgentTurn 的迭代、Step、预算、重试和工具错误处理能力。
---

# AgentExecutionPolicy

## 概述

`AgentExecutionPolicy` 是 Agent 执行控制的统一配置。它决定一次 `AgentTurn` 能执行多久、调用多少次模型和工具，以及异常发生后如何继续。

策略可以通过 `Agent.Builder.executionPolicy(...)` 作为默认策略，也可以通过 `AgentTurnOptions.executionPolicy(...)` 只覆盖某一次 Turn。解析后的策略会随 Snapshot 保存，恢复时不会因为 Agent 默认配置变化而漂移。

## 配置项

| 配置 | 作用 |
| --- | --- |
| `maxIterations` | 最大模型调用次数，防止模型和工具无限循环 |
| `maxSteps` | Runner 最大推进 Step 数，覆盖非模型路径的循环 |
| `budget` | 限制时长、输入/输出/总 Token 和工具调用次数 |
| `retryPolicy` | 配置可恢复异常的重试次数、退避和最长等待 |
| `toolErrorStrategy` | 工具失败后终止 Turn，或把错误交回模型 |
| `toolErrorMessageFactory` | 自定义交回模型的错误消息、脱敏和业务错误码 |
| `interruptedToolMessageTemplate` | 取消时未完成 ToolCall 的收束内容 |
| `interruptedTurnMessageTemplate` | 取消或异常终止时追加的 AIMessage 内容 |
| `cancellationReason` | `cancel(...)` 使用的默认原因文本 |

## 基本配置

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
        .initialDelayMillis(500)
        .maxDelayMillis(10_000)
        .build())
    .build();

Agent agent = Agent.builder("support-agent")
    .chatModel(chatModel)
    .executionPolicy(policy)
    .build();
```

## 工具错误

`toolErrorStrategy` 负责控制流：

```java
AgentExecutionPolicy.builder()
    .toolErrorStrategy(ToolErrorStrategy.RETURN_ERROR_TO_MODEL)
    .toolErrorMessageFactory((turn, call, error) -> {
        ToolMessage message = new ToolMessage();
        message.setContent("{\"code\":\"UPSTREAM_UNAVAILABLE\"}");
        return message;
    })
    .build();
```

- `FAIL_RUN`：工具异常直接让 Turn 失败。
- `RETURN_ERROR_TO_MODEL`：Runner 生成与原 ToolCall 匹配的 `ToolMessage`，让模型决定重试、换工具或向用户解释。
- Factory 只负责消息内容；Runner 会强制写入原始 `toolCallId`，保证协议完整。

## 单次 Turn 覆盖

```java
AgentTurnOptions options = AgentTurnOptions.builder()
    .streaming(true)
    .executionPolicy(AgentExecutionPolicy.builder()
        .maxIterations(3)
        .maxSteps(30)
        .budget(AgentBudget.builder().maxTotalTokens(8_000).build())
        .build())
    .metadata("taskType", "quick-query")
    .build();

AgentTurn turn = runner.run(agent, "查询订单状态", options);
```

单次覆盖是整套策略替换，不是字段级合并。需要保留默认的重试、错误和预算配置时，应显式复制这些配置后再构建覆盖策略。

## 超限后的状态

| 原因 | Turn 状态 | 说明 |
| --- | --- | --- |
| 迭代次数超限 | `MAX_ITERATIONS_REACHED` | 不再调用模型或工具 |
| Step 超限 | `MAX_STEPS_REACHED` | 不再调用模型或工具 |
| 时长超限 | `BUDGET_EXCEEDED` | Runner 在外部调用边界检查 |
| Token 超限 | `BUDGET_EXCEEDED` | 模型返回后依据 Usage 检查 |
| 工具调用次数超限 | `BUDGET_EXCEEDED` | 真实业务 Tool 执行前检查 |
| 可恢复异常 | `RETRY_SCHEDULED` | 由 Worker 在 `nextRunnableAt` 到达后继续 |

## 选择建议

- `maxIterations` 控制模型循环，`maxSteps` 控制整个 Runner 状态机；两者应同时设置。
- 外部副作用工具必须使用幂等键，并谨慎配置自动重试。
- 生产环境不要把底层堆栈直接返回模型，使用 `ToolErrorMessageFactory` 脱敏。
- 预算耗尽是业务终态，是否向用户展示、创建人工工单或允许新 Turn 由业务系统决定。

相关文档：[预算控制](./budget)、[错误重试](./retry)、[AgentRunner](./agent-runner)。
