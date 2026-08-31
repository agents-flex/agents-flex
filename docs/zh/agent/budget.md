---
title: 预算控制
description: 限制 AgentTurn 的时长、Token、工具调用、模型迭代和 Runner step。
---

# 预算控制

## 概述

Agent 的执行路径由模型动态决定，必须设置资源边界。`AgentExecutionPolicy` 限制模型迭代和 Runner 总 step，并配置重试和错误策略；`AgentBudget` 限制时长、Token 和工具调用次数。

预算是硬停止条件，不是计费系统。超过任一维度后 Turn 进入终态 `BUDGET_EXCEEDED`，不会自动重试。

## 配置预算

```java
AgentBudget budget = AgentBudget.builder()
    .maxDurationMillis(120_000)
    .maxInputTokens(50_000)
    .maxOutputTokens(10_000)
    .maxTotalTokens(60_000)
    .maxToolCalls(20)
    .build();

AgentExecutionPolicy policy = AgentExecutionPolicy.builder()
    .maxIterations(10)
    .maxSteps(40)
    .budget(budget)
    .build();
```

值为 `0` 表示该维度不限制。`AgentBudget.unlimited()` 关闭全部预算，但生产环境通常不应这样配置。

## 检查时机

- 总时长从 Turn 创建时间开始计算，包括等待审批和重试的时间。
- 时长在外部调用前检查，因此不能强制中断已经开始的模型或工具调用。
- Token 用量在模型响应后累计并检查，可能略微超过上限。
- 工具次数在即将执行新工具前检查，已完成调用不会被倒推为失败。
- `maxIterations` 统计模型请求；`maxSteps` 统计 Runner 进入稳定执行步骤的次数，包括规划推进和内置 ToolCall 状态机推进。

模型实现若不返回 Token usage，Token 预算无法得到准确统计，应同时使用迭代、工具和时长限制。

## 单次运行覆盖

```java
AgentTurnOptions options = AgentTurnOptions.builder()
    .executionPolicy(AgentExecutionPolicy.builder()
        .maxIterations(4)
        .budget(AgentBudget.builder().maxTotalTokens(8_000).build())
        .build())
    .build();

AgentTurn turn = runner.run(agent, input, options);
```

实际策略写入 Snapshot，恢复时不会跟随 Agent 默认策略漂移。覆盖是整套 `AgentExecutionPolicy`，调用方应显式保留需要的重试和错误策略。

## 读取结果

```java
if (turn.getStatus() == AgentTurnStatus.BUDGET_EXCEEDED) {
    log.warn("budget exceeded: {}", turn.getBudgetExceededReason());
}
```

原因可能是 `maxDurationMillis`、`maxInputTokens`、`maxOutputTokens`、`maxTotalTokens` 或 `maxToolCalls`。`getBudgetExceededReason()` 和 `BUDGET_EXCEEDED` 事件会保留原因键名，并附带实际用量与限制值，例如 `maxTotalTokens (used=120, limit=100)`；时间预算则会显示 `elapsed` 和 `limit` 的毫秒数。模型迭代和 Runner step 达到上限使用独立终态，不通过 budget reason 表达。

## 分层预算

平台可按 Agent 类型、租户套餐和任务风险选择策略，但应在创建 Turn 前计算最终值。每个 Turn 独立计数；跨 Turn 的配额由上层平台按业务会话聚合。

## 生产建议

- 同时配置合理的 `maxIterations` 和 `maxSteps`，后者还能约束不发起模型请求的规划推进路径。
- 对有费用或副作用的工具设置 `maxToolCalls`。
- 监控预算终止比例，过高可能意味着提示词、工具错误或阈值不合理。
- 不把预算耗尽包装成普通最终答案；向调用方明确返回受限状态。
- 对外部 HTTP 客户端单独设置连接、读取和总超时，Agent 时长预算不能代替网络超时。
