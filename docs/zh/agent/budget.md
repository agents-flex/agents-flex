---
title: 运行限制与预算
description: 限制 Agent 任务的模型调用、执行步骤、运行时间、Token 用量和工具调用次数。
---

# 运行限制与预算

## 概述

Agent 会根据大模型的判断反复调用模型和工具，实际执行次数无法在开发时完全确定。因此，每个任务都应设置合理的运行边界，避免长时间循环、费用失控或工具被过多调用。

Agents-Flex 提供两类限制：

| 限制类型 | 配置 | 作用 |
| --- | --- | --- |
| 执行次数 | `maxIterations`、`maxSteps` | 限制模型调用次数和 Runner 推进次数 |
| 资源预算 | `AgentBudget` | 限制总时长、Token 用量和工具调用次数 |

这些限制通过 `AgentExecutionPolicy` 统一配置，但业务文档按具体能力分别说明，不需要先理解该类的全部字段。

默认情况下，模型调用上限为 100 次，Runner 执行步骤上限为 1,000 次，资源预算不设上限。默认值主要用于兼容通用场景，生产应用仍应根据任务类型显式配置。

## 基本配置

```java
AgentExecutionPolicy policy = AgentExecutionPolicy.builder()
    .maxIterations(10)
    .maxSteps(40)
    .budget(AgentBudget.builder()
        .maxDurationMillis(120_000)
        .maxInputTokens(50_000)
        .maxOutputTokens(10_000)
        .maxTotalTokens(60_000)
        .maxToolCalls(20)
        .build())
    .build();

Agent agent = Agent.builder("report-assistant")
    .chatModel(chatModel)
    .executionPolicy(policy)
    .build();
```

该策略会作为 Agent 创建的所有任务的默认限制。

## 执行次数限制

### 最大模型调用次数

`maxIterations(...)` 限制一次 AgentTurn 最多调用多少次大模型。

模型调用工具后，Runner 通常还要再次调用模型，让模型读取工具结果并继续回答。因此，迭代次数不能简单理解为用户提问次数。

达到上限后，任务进入 `MAX_ITERATIONS_REACHED` 状态。

### 最大执行步骤

`maxSteps(...)` 限制 Runner 最多推进多少个执行步骤。一个步骤可能是调用模型，也可能是处理工具或任务状态。

它是模型调用次数之外的第二层保护，可以避免不调用模型的执行路径持续循环。达到上限后，任务进入 `MAX_STEPS_REACHED` 状态。

通常应同时设置 `maxIterations` 和 `maxSteps`，并让 `maxSteps` 明显大于 `maxIterations`。

## 资源预算

`AgentBudget` 支持以下限制：

| 配置 | 作用 |
| --- | --- |
| `maxDurationMillis(...)` | 从任务创建开始计算的最长总时间 |
| `maxInputTokens(...)` | 累计发送给模型的 Token 上限 |
| `maxOutputTokens(...)` | 累计由模型生成的 Token 上限 |
| `maxTotalTokens(...)` | 模型报告的累计总 Token 上限 |
| `maxToolCalls(...)` | 最多执行多少次业务工具 |

Token 是大模型统计文本用量的基本单位，也通常会影响调用费用。

预算值为 `0` 表示不限制该项。`AgentBudget.unlimited()` 会关闭全部资源预算，不建议在生产环境中直接使用。

## 限制生效时机

- 总时长从 AgentTurn 创建时开始计算，包括等待审批和自动重试的时间。
- 时间和工具次数会在新的外部调用开始前检查。
- Token 用量只能在模型返回用量信息后累计，因此实际值可能略微超过上限。
- 如果模型服务不返回 Token 用量，Token 预算无法准确生效，应同时设置次数和时间限制。

总时长限制不能强制中断已经开始的模型或工具调用。单次调用超时应另外配置，详见[超时与过期](./timeouts)。

## 超限状态

| 超限原因 | AgentTurn 状态 |
| --- | --- |
| 模型调用次数 | `MAX_ITERATIONS_REACHED` |
| Runner 执行步骤 | `MAX_STEPS_REACHED` |
| 总时长、Token 或工具调用次数 | `BUDGET_EXCEEDED` |

可以读取具体的预算超限原因：

```java
if (turn.getStatus() == AgentTurnStatus.BUDGET_EXCEEDED) {
    System.out.println(turn.getBudgetExceededReason());
}
```

预算耗尽是任务的结束状态，不会自动重试。业务系统可以向用户说明限制，也可以根据任务类型创建新的任务或转人工处理。

## 单次任务覆盖

如果某一次任务需要使用不同限制，可以通过 `AgentTurnOptions` 覆盖：

```java
AgentTurnOptions options = AgentTurnOptions.builder()
    .executionPolicy(AgentExecutionPolicy.builder()
        .maxIterations(4)
        .maxSteps(20)
        .budget(AgentBudget.builder()
            .maxTotalTokens(8_000)
            .maxToolCalls(5)
            .build())
        .build())
    .build();

AgentTurn turn = runner.run(agent, "快速查询订单状态", options);
```

单次配置会替换整套执行策略，而不是只合并其中几个字段。如果还需要 Agent 默认的重试、工具错误或超时配置，应在新策略中一并设置。

## 配置建议

1. 为所有生产任务设置模型调用次数和执行步骤上限。
2. 对有费用或业务影响的工具设置 `maxToolCalls`。
3. 同时使用 Token、时间和次数限制，不依赖单一指标。
4. 监控各种超限状态，持续调整提示词、工具和限制值。
5. 跨多个 AgentTurn 的租户配额由业务系统统计，单个 Turn 的预算不能代替账号配额。

## 相关文档

- 单次模型和工具调用超时：[超时与过期](./timeouts)。
- 异常后的处理方式：[错误处理与重试](./retry)。
- 多个工具的执行方式：[工具执行控制](./tool-execution)。
