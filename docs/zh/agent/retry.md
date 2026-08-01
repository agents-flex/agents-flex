---
title: 错误重试与预算控制
---

# 错误重试与预算控制

## 概述

重试用于处理暂时性故障，预算用于限制一次 AgentRun 最多消耗多少时间、Token、工具调用和执行步骤。两者共同决定执行何时继续、何时等待、何时必须终止。

框架把策略保存进 Snapshot，因此后台 Worker 恢复后仍使用任务创建时的约束，不会因为当前 Agent 配置已改变而悄悄放宽成本上限。

## 快速开发

```java
AgentExecutionPolicy policy = AgentExecutionPolicy.builder()
    .maxIterations(8)
    .maxSteps(24)
    .retryPolicy(AgentRetryPolicy.builder()
        .maxRetries(3)
        .initialDelayMillis(1_000)
        .maxDelayMillis(30_000)
        .multiplier(2.0)
        .build())
    .budget(AgentBudget.builder()
        .maxDurationMillis(120_000)
        .maxTotalTokens(20_000)
        .maxToolCalls(12)
        .build())
    .build();

Agent agent = Agent.builder()
    .id("report-agent")
    .version("1")
    .chatModel(chatModel)
    .executionPolicy(policy)
    .build();
```

## 自动重试调度

可重试错误发生后，Runner 不会占用线程等待。它计算指数退避时间，将 Run 置为 `RETRY_SCHEDULED`，把 `nextRunAt` 写入 Checkpoint，然后返回。到期后同步调用方可再次恢复，后台 Worker 也可自动领取。

重试会保留当前待处理 ToolCall，工具失败后不会要求模型重新生成一组可能不同的参数。对于创建订单等有副作用操作，工具仍须使用 invocation ID 实现业务幂等。

`maxRetries` 表示首次失败后的额外尝试次数。重试次数用尽后，Run 进入 `FAILED`。如果某类错误确定不可恢复，应通过工具错误策略或 Middleware 直接让其失败，避免无意义地重试参数校验、权限拒绝等确定性错误。

## 工具错误策略

工具错误可以进入统一失败/重试流程，也可以被转换为 ToolMessage 交给模型修正参数或选择其他工具。选择策略时应区分：

- 网络超时、限流等暂时故障适合调度重试；
- 参数错误适合反馈给模型修正；
- 权限校验失败和明确的业务拒绝通常应直接结束或等待人工处理。

不要用模型反复调用来代替基础设施重试，否则会增加 Token 成本，也可能生成不同参数。

## 时间与 Token 预算

`AgentBudget` 可分别限制：

- `maxDurationMillis`：Run 从创建开始的总持续时间；
- `maxInputTokens`：累计输入 Token；
- `maxOutputTokens`：累计输出 Token；
- `maxTotalTokens`：输入与输出总和；
- `maxToolCalls`：工具调用总次数。

超过任一限制后，Run 进入 `BUDGET_EXCEEDED`，并在 Snapshot 中记录原因。时间预算包含审批和重试等待时间，因此需要等待数小时人工确认的业务，应设置与业务 SLA 匹配的时间上限。

Token 预算依赖模型返回的 usage 数据。如果模型供应商不提供准确 usage，应用应使用模型侧限额或 Middleware 估算作为补充保护。

## 迭代与 step 上限

`maxIterations` 限制模型调用轮数，达到上限时状态为 `MAX_ITERATIONS_REACHED`。`maxSteps` 限制执行模式推进次数，能够约束不调用模型的自定义模式，达到上限时状态为 `MAX_STEPS_REACHED`。

两者不是任务质量参数。复杂任务更适合通过规划、子 Agent 和明确工具拆分降低单个循环复杂度，而不是无限提高上限。

## 取消

```java
runner.requestCancellation(runId);
```

取消标记持久化在 Store 中，不要求控制面持有 Worker Lease。Runner 会在可控边界检查它并进入 `CANCELLED`。已经发出的模型 HTTP 请求或不可中断业务工具不保证立即停止，所以外部调用仍应设置自身超时。

## 生产建议

预算应由平台给出硬上限，业务 Agent 只能在上限内收紧。重试策略应记录错误类型、尝试次数和下次执行时间，并通过持久化事件生成告警。对于外部服务持续失败的场景，还应在工具层配合熔断和限流，而不是把所有压力留给 Agent 重试循环。

