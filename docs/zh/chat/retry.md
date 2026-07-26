---
title: 错误重试
description: 配置模型请求重试，并使用 Retryer 为其他瞬时失败操作定义固定或指数退避策略。
---

# 错误重试

<div v-pre>

## 概述

Agents-Flex 有两类容易混淆的重试：

- `ChatConfig` / `ChatOptions` 的请求重试，由 `OpenAIChatClient` 调用 `Retryer.retry(...)`。
- Model Router 的节点重试，在一个节点失败后重新选择可用 Endpoint。

本篇介绍前者和通用 `Retryer`。多节点切换见 [LLM 负载均衡与高可用](../intro/model-router.md)。

## 适用场景

- 连接超时、断开、DNS 或短暂 I/O 故障。
- 明确可恢复且重复执行没有额外副作用的请求。
- 需要为自定义远程 Tool 定义总超时和退避。

认证失败、参数错误、内容审核拒绝和持续性服务错误不应靠重试解决。对于会产生副作用的 Tool，必须先实现幂等键。

## 快速开始

模型配置默认启用，重试 3 次，初始间隔 1000ms：

```java
OpenAIChatConfig config = new OpenAIChatConfig();
config.setRetryEnabled(true);
config.setRetryCount(2);
config.setRetryInitialDelayMs(500);
```

单次请求覆盖模型配置：

```java
ChatOptions options = ChatOptions.builder()
    .retryEnabled(true)
    .retryCount(1)
    .retryInitialDelayMs(300)
    .build();

chatModel.chat(prompt, options);
```

Options 优先于 Config。关闭重试后，`ChatRequestSpec.retryCount` 会被设置为 0。

## 模型请求的实际策略

`OpenAIChatClient` 调用静态 `Retryer.retry(task, count, delay)`。该便捷方法没有开启指数退避，因此每次等待都是相同的 `initialDelayMs`。

默认异常谓词只重试网络相关异常，例如 `SocketTimeoutException`、`ConnectException`、`UnknownHostException`、`IOException` 及部分直接 cause。HTTP 状态是否表现为可重试异常取决于 `AgentsFlexHttpClient` 的异常行为。

同步响应中的 JSON `error`、空响应和解析错误是在网络调用完成后处理，不会由这一层自动重试。

::: warning 流式请求
流式重试包围的是 `streamClient.start(...)`。连接启动后通过异步 Listener 报告的中途失败不会回到这个同步 `Retryer` 中重试。
:::

## 通用 Retryer

需要指数退避、总截止时间或结果判断时，使用 Builder：

```java
Retryer retryer = Retryer.builder()
    .maxRetries(3)
    .initialDelayMs(200)
    .maxDelayMs(2_000)
    .exponentialBackoff()
    .totalTimeoutMs(5_000)
    .operationName("load_order")
    .retryOnException(e -> e instanceof IOException)
    .retryOnResult(result -> result == null)
    .build();

Order order = retryer.execute(() -> orderClient.load(orderNo));
```

`maxRetries` 不包含首次执行，因此 3 表示最多 4 次尝试。Builder 默认值是 2 次重试、100ms 固定延迟、最大延迟 5000ms、无总超时。

## 超时与中断

`totalTimeoutMs` 是整个执行与等待过程的截止时间，不会主动取消一次已经阻塞且不响应中断的底层调用；网络客户端仍应配置 connect/read/call timeout。

线程在执行前已中断，或退避 sleep 被中断时，Retryer 会恢复中断标记并终止重试。

## 生产建议

1. 只重试明确的瞬时异常，不使用“所有 Exception”谓词。
2. 设置总截止时间，并让单次网络超时小于它。
3. 429 应结合服务端 `Retry-After`；当前通用 Retryer 不自动读取该 Header。
4. 监控尝试次数、最终失败率和额外延迟，避免重试放大故障。
5. 多层重试同时开启时计算最坏请求次数，例如 Client 重试乘以 Router 节点重试。

## 常见问题

### 默认是指数退避吗？

不是。Builder 只有显式调用 `.exponentialBackoff()` 才指数增长；模型 Client 使用的静态便捷方法是固定延迟。

### `retryCount=0` 会执行请求吗？

会执行一次，只是不重试。

### 为什么 API 返回 error JSON 没有重试？

网络调用已经成功返回，错误是在解析阶段变成 `AiMessageResponse`，不属于默认异常重试范围。

## 下一步

- [LLM 负载均衡与高可用](../intro/model-router.md)
- [ChatClient](./chat-client.md)
- [日志](./logger.md)

</div>
