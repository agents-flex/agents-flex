---
title: LLM 负载均衡与高可用
description: 使用 RoutedChatModel 和 RoutedEmbeddingModel 在多个模型节点间做负载均衡、标签路由、重试与熔断。
---

# LLM 负载均衡与高可用

## 概述

单个模型 Endpoint 可能限流、超时或临时不可用。Model Router 把多个 `ChatModel` 或 `EmbeddingModel` 包装成一个同类型模型，对上层保持统一接口，并在内部完成：

```text
过滤状态/标签/熔断节点 -> 负载均衡选择
 -> 调用 -> 记录指标 -> 成功返回或重新选择
```

Router 处理的是应用进程内的模型实例路由，不会自动做跨进程健康探测、分布式状态同步或配置发现。

## 适用场景

- 同一模型部署多个 Endpoint，需要分摊并发。
- 主供应商失败时切换到兼容的备用模型。
- 按 `vision`、`reasoning`、`cheap` 等标签选择节点。
- 对 Chat 与 Embedding 使用一致的熔断和选择策略。

只有一个 Endpoint 时直接使用原 `ChatModel` 更简单。不同模型的能力、上下文长度和输出语义差异很大，加入同一 Router 前应确认业务可以接受切换。

## 快速开始

便捷构造函数使用最少活跃负载均衡、`DefaultRetryPolicy(3)` 和默认熔断器：

```java
ChatModel primary = new OpenAIChatModel(primaryConfig);
ChatModel backup = new OpenAIChatModel(backupConfig);

ChatModel routed = new RoutedChatModel(
    Arrays.asList(primary, backup)
);

AiMessageResponse response = routed.chat(prompt, options);
```

`maxRetries=3` 不包含首次调用，因此最坏会尝试 4 次；每次循环会重新过滤并选择 Endpoint，但负载均衡器仍可能再次选中同一可用节点。

## 核心对象

| 对象 | 职责 |
| --- | --- |
| `ModelEndpoint<T>` | 保存模型、权重、标签、状态与运行时指标 |
| `ModelLoadBalancer<T>` | 从当前候选节点中选择一个 |
| `CircuitBreaker<T>` | 判断是否允许请求并记录成功/失败 |
| `RetryPolicy` | 根据次数和 Throwable 决定是否继续 |
| `RoutedChatModel` | 实现标准 ChatModel 接口 |
| `RoutedEmbeddingModel` | 实现标准 EmbeddingModel 接口 |

Endpoint 状态有 `UP`、`DOWN` 和 `HALF_OPEN`。Router 会直接过滤 `DOWN`，但默认熔断器需要在 `allowRequest(...)` 中根据恢复时间把节点转为 HALF_OPEN；过滤顺序允许熔断器处理非 DOWN 候选这一点取决于状态。当前 `filterEndpoints` 首先过滤所有 DOWN，因此被设置为 DOWN 的节点不会进入默认熔断器的恢复判断。这意味着自动半开恢复存在实现限制，生产使用前应通过测试确认或提供自定义策略/外部状态恢复。

## 自定义路由

```java
ModelEndpoint<ChatModel> fast = new ModelEndpoint<>(fastModel);
fast.setWeight(5);
fast.addTags(Collections.singleton("fast"));

ModelEndpoint<ChatModel> vision = new ModelEndpoint<>(visionModel);
vision.setWeight(1);
vision.addTags(Collections.singleton("vision"));

RoutedChatModel routed = new RoutedChatModel(
    Arrays.asList(fast, vision),
    new WeightedRandomLoadBalancer<>(),
    new DefaultRetryPolicy(2),
    new DefaultCircuitBreaker<>(3, 10_000)
);
```

请求标签来自 `ChatOptions` metadata 中名为 `modelTags` 的 `Set<String>`：

```java
Set<String> tags = Collections.singleton("vision");
ChatOptions options = ChatOptions.builder()
    .metadata("modelTags", tags)
    .build();
```

Endpoint 必须包含全部请求标签才会成为候选。不是 Set 的值会被当作空标签处理。

## 负载均衡

- `LeastActiveLoadBalancer` 优先选择当前活跃请求较少的节点。
- `WeightedRandomLoadBalancer` 按 Endpoint 权重随机选择，权重最小被限制为 1。

指标由 Router 在调用前 `beginRequest()`，并在 finally 中 `endRequest()`；成功和失败都会记录延迟。

## 重试与熔断

`DefaultRetryPolicy` 只检查次数，不区分异常类型，也没有延迟。需要排除认证、参数和业务错误时，应实现自定义 `RetryPolicy`。

默认熔断器连续失败阈值为 5，恢复时间为 30 秒；成功会把连续失败清零并设置 UP。由于前述 DOWN 过滤顺序，不能把它当作已经完整验证的自动恢复机制。

Router 重试与每个 Chat Client 自身的网络重试会叠加。应按最坏情况计算总请求次数和延迟。

::: warning 流式调用
`RoutedChatModel.chatStream(...)` 在启动底层异步流后立即把本次 execute 视为成功。只有 `chatStream` 启动阶段同步抛出的异常会触发 Router 重试；连接建立后的 `onError` 不会自动切换 Endpoint。
:::

## Embedding 路由

```java
EmbeddingModel routedEmbedding = new RoutedEmbeddingModel(
    Arrays.asList(embeddingA, embeddingB)
);
```

调用方式与普通 EmbeddingModel 相同。切换节点前应确保向量维度和语义空间一致，否则同一索引中的向量不可直接混用。

## 生产建议

1. 同组 ChatModel 使用兼容的工具、多模态和响应能力。
2. 自定义 RetryPolicy 只重试瞬时错误，并增加有界退避。
3. 为熔断恢复、所有节点 DOWN、标签无候选和并发指标编写测试。
4. 节点状态只在当前 Router 实例内共享；多实例部署需要外部健康状态或各自独立容错。
5. 对流式中途失败在应用 Listener 层设计恢复策略，避免重复输出已发送内容。

## 常见问题

### 为什么标签路由没有生效？

metadata key 必须是 `modelTags`，值必须是 `Set<String>`，Endpoint 必须包含全部标签。

### 为什么备用节点也失败后请求次数很多？

Router 重试和单节点 Chat Client 重试可能同时开启。检查两层 retryCount。

### Router 会保证重试选择不同节点吗？

不会。每轮重新调用负载均衡器，仍可能选到未熔断的同一节点。

## 下一步

- [错误重试](../chat/retry.md)
- [ChatModel](../chat/chat-model.md)
- [ChatConfig](../chat/chat-config.md)
