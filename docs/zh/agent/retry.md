---
title: 错误处理与重试
description: 配置模型和工具失败后的处理方式、自动重试、错误消息转换与异常结束提示。
---

# 错误处理与重试

## 概述

Agent 运行时可能遇到模型限流、网络异常、工具失败或参数错误。不同错误适合不同处理方式：临时故障可以稍后重试，确定性错误应立即失败，部分工具错误也可以交给大模型解释或选择其他工具。

Agents-Flex 通过以下配置处理这些情况：

| 配置 | 作用 |
| --- | --- |
| `AgentRetryPolicy` | 设置重试次数和每次重试前的等待时间 |
| `AgentRetryDecider` | 判断当前错误是否适合重试 |
| `ToolErrorStrategy` | 决定工具错误是终止任务，还是交回模型处理 |
| `ToolErrorMessageFactory` | 控制模型看到的工具错误内容 |

这些能力最终通过 `AgentExecutionPolicy` 配置到 Agent。

## 配置自动重试

```java
AgentRetryPolicy retryPolicy = AgentRetryPolicy.builder()
    .maxRetries(3)
    .initialDelayMillis(1_000)
    .multiplier(2.0)
    .maxDelayMillis(30_000)
    .build();

AgentExecutionPolicy policy = AgentExecutionPolicy.builder()
    .retryPolicy(retryPolicy)
    .build();
```

上面的配置表示：首次执行失败后最多重试 3 次，第一次等待 1 秒，之后按 2 倍增加，但单次等待不超过 30 秒。

`maxRetries` 不包含第一次执行。`AgentRetryPolicy.none()` 表示关闭自动重试，也是默认行为。

## 重试过程

发生可重试错误后，Runner 不会让当前线程一直等待，而是：

1. 保存当前任务进度和错误信息；
2. 将 AgentTurn 状态更新为 `RETRY_SCHEDULED`；
3. 记录下次允许执行的时间；
4. 等待时间到达后，由 AgentWorker 继续原任务。

这样，即使服务在等待期间重启，任务仍然可以从已保存的位置继续。

## 重试范围

默认情况下，网络异常、模型限流和服务过载等临时故障可以重试。以下错误通常不会直接重试：

- 参数格式错误；
- 找不到模型指定的工具；
- 模型上下文超过限制；
- 模型账号余额或配额耗尽；
- 任务被取消；
- 任务达到运行次数或资源预算上限。

应用可以通过 `retryDecider(...)` 自定义判断逻辑：

```java
AgentExecutionPolicy policy = AgentExecutionPolicy.builder()
    .retryPolicy(retryPolicy)
    .retryDecider((turn, error, toolCall) ->
        error instanceof ModelRateLimitException
            || error instanceof ModelOverloadedException)
    .build();
```

自定义判断器应区分临时故障和业务错误。订单不存在、参数无效等确定性错误不应通过重复执行来解决。

## 工具错误处理

工具执行失败时，可以选择两种处理方式：

| 策略 | 行为 | 适用场景 |
| --- | --- | --- |
| `FAIL_RUN` | 进入重试流程；不能重试时结束任务 | 写入失败、安全检查失败等不能交给模型处理的错误 |
| `RETURN_ERROR_TO_MODEL` | 把错误作为工具结果交回模型 | 模型可以改用其他工具或向用户解释的错误 |

默认策略是 `FAIL_RUN`。

配置示例：

```java
AgentExecutionPolicy policy = AgentExecutionPolicy.builder()
    .toolErrorStrategy(ToolErrorStrategy.RETURN_ERROR_TO_MODEL)
    .build();
```

将错误交回模型并不等于自动重试。模型会根据错误内容决定修改参数、选择其他工具或直接说明失败。

## 错误信息转换

生产环境不应把异常堆栈、数据库信息或内部地址直接发送给大模型。可以通过 `toolErrorMessageFactory(...)` 转换错误内容：

```java
AgentExecutionPolicy policy = AgentExecutionPolicy.builder()
    .toolErrorStrategy(ToolErrorStrategy.RETURN_ERROR_TO_MODEL)
    .toolErrorMessageFactory((turn, call, error) -> {
        ToolMessage message = new ToolMessage();
        message.setContent(
            "{\"code\":\"UPSTREAM_UNAVAILABLE\","
                + "\"message\":\"订单服务暂时不可用\"}"
        );
        return message;
    })
    .build();
```

Runner 会保留原工具调用的关联信息，Factory 只需要提供适合模型读取的错误内容。

## 异常结束消息

任务取消或最终失败时，Runner 会补充结束说明，避免后续聊天历史停留在未完成的工具调用上。可以自定义这些内容：

```java
AgentExecutionPolicy policy = AgentExecutionPolicy.builder()
    .interruptedToolMessageTemplate("工具 {toolName} 未完成：{reason}")
    .interruptedTurnMessageTemplate("本次任务未完成：{reason}")
    .cancellationReason("用户主动停止任务")
    .build();
```

这类模板主要用于后续模型上下文，不应代替面向用户的错误页面、日志或审计信息。

## 重试状态

```java
if (turn.getStatus() == AgentTurnStatus.RETRY_SCHEDULED) {
    System.out.println("已重试次数：" + turn.getRetryCount());
    System.out.println("下次执行时间：" + turn.getNextRunnableAt());
}
```

`RETRY_SCHEDULED` 表示任务尚未结束。重试次数用尽后，任务进入 `FAILED`。

## 工具的防重复执行

工具可能已经完成业务操作，但 Runner 在保存结果前发生故障。任务恢复后，同一个工具调用可能再次执行。

退款、扣款、发货、发送邮件等工具必须使用稳定的幂等键识别重复请求：

```java
String idempotencyKey = AgentToolContext.current().getIdempotencyKey();
```

业务数据库应保存该键，并在收到重复请求时返回第一次执行结果，而不是再次产生业务影响。

## 配置建议

1. 只重试可能自行恢复的临时故障。
2. 写入类工具必须具备防重复执行能力。
3. 避免 HTTP 客户端重试和 Agent 重试无限叠加。
4. 对模型错误、工具错误、重试次数和最终状态分别记录指标。
5. 工具错误交回模型前应进行脱敏，并提供明确的错误代码。

## 相关文档

- 配置单次调用超时：[超时与过期](./timeouts)。
- 限制任务总体消耗：[运行限制与预算](./budget)。
- 后台执行延迟重试：[后台任务 Worker](./worker)。
