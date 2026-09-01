---
title: 错误重试
description: 配置指数退避、工具错误策略和持久化重试，并确保副作用幂等。
---

# 错误重试

## 概述

AgentRunner 将可恢复异常转换为持久化重试状态，而不是在执行线程中 sleep。Turn 保存失败阶段与 `nextRunnableAt`，进入 `RETRY_SCHEDULED`；时间到达后由 Worker 领取并从原 INVOKE_MODEL 或 PROCESS_TOOLS 边界继续。

## 配置重试

```java
AgentRetryPolicy retry = AgentRetryPolicy.builder()
    .maxRetries(3)
    .initialDelayMillis(1_000)
    .multiplier(2.0)
    .maxDelayMillis(30_000)
    .build();

AgentExecutionPolicy policy = AgentExecutionPolicy.builder()
    .retryPolicy(retry)
    .build();
```

第 n 次重试采用指数退避，并受最大延迟限制。`AgentRetryPolicy.none()` 禁止自动重试。

## 哪些异常会重试

Runner 不重试确定性的参数错误和工具不存在错误；其他运行时异常在次数允许时可安排重试。应用不应依赖过于宽泛的默认分类来掩盖业务错误，工具内部应把不可恢复输入错误表达清楚，并在必要时由 Middleware 标准化异常。

ChatModel 会把部分供应商错误归一化为结构化异常：`ModelRateLimitException`（临时限速）和
`ModelOverloadedException`（服务过载）可以按退避策略重试；`TokenLimitExceededException`（输入上下文
超限）和 `ModelQuotaExceededException`（余额、消费或组织额度耗尽）不会原样重试。上下文超限应先缩短
消息窗口或摘要后重新构造 Prompt，额度耗尽则需要业务处理账户或项目配置。

取消请求优先于重试。预算耗尽、最大迭代和最大 step 是终态，也不会进入重试。

## 工具错误策略

`ToolErrorStrategy` 决定工具异常是否终止本次 Turn，或被编码为结构化 `ToolMessage` 交回模型处理。交回模型适合“某个数据源暂不可用，可选择其他工具”；直接失败适合安全边界和不可继续的业务写入。

工具不存在与参数非法即使交回模型，也必须谨慎控制，避免模型在错误循环中持续消耗迭代预算。

## Worker 执行

```java
try (AgentWorker worker = new AgentWorker("worker-a", runner, 30_000)) {
    worker.startPolling(1_000, 10);
}
```

`AgentTurnStore.claimRunnable` 只有在 `nextRunnableAt <= now` 时才允许领取重试 Turn。Store 应提供统一 `currentTimeMillis()`，多实例部署应避免应用服务器时钟差导致提前或延迟领取。

## 工具副作用幂等

重试可能发生在“业务操作已经成功，但 Snapshot 尚未提交”的窗口。工具应通过
`AgentToolContext.current()` 读取受控执行上下文，并使用稳定的幂等键：

```java
AgentToolContext context = AgentToolContext.current();
String idempotencyKey = context.getIdempotencyKey();
```

数据库写入应对该键建立唯一约束，并在重复调用时返回第一次结果。Agent Store 的乐观锁只能保护 Snapshot，不能自动回滚外部支付、工单或邮件系统。

## 观察重试

读取 `turn.getRetryCount()`、`turn.getNextRunnableAt()` 和 Suspension metadata，并监听 `RETRY_SCHEDULED` 事件。告警应区分最终失败与暂定重试，避免第一次瞬时错误就触发高优先级告警。

## 生产建议

- 网络客户端自身重试与 Agent 重试不要无界叠加。
- 写工具必须业务幂等，读工具也应控制超时。
- 对失败类型、重试次数和最终结果建立指标。
- Store 暂时不可用时不要继续产生外部副作用，因为无法提交稳定边界。
